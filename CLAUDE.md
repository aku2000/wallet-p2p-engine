# CLAUDE.md

> This file provides guidance to Claude AI when working with code in this repository.
> It is the authoritative source of project conventions, constraints, and context.
> Read this before making any code changes.

---

## Project Overview

`wallet-p2p-engine` is a peer-to-peer wallet service built for correctness under high concurrency.
It handles wallet creation and P2P money transfers between users, with zero tolerance for:
- Balance errors (money created or destroyed)
- Negative balances (overdraft)
- Duplicate transfers (double-spend)
- Race conditions on wallet creation

**This is a financial system. Correctness > Performance > Convenience.**

---

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Java 21 (use virtual threads where appropriate) |
| Framework | Spring Boot 3.3, WebMVC (NOT WebFlux) |
| DB Access | Spring JDBC + `JdbcTemplate` (raw SQL — NO JPA, NO Hibernate) |
| Database | PostgreSQL 16 |
| Migrations | Flyway (auto-runs on startup, classpath: `db/migration/`) |
| Connection Pool | HikariCP (Spring Boot default) |
| Metrics | Micrometer + Spring Actuator + custom `/dashboard` HTML endpoint |
| Logging | Logback + `logstash-logback-encoder` (structured JSON) |
| Build | Maven 3.9 |
| Containerization | Docker (multi-stage build) + Docker Compose |
| Deployment | Render.com (free tier) |
| Tests | JUnit 5 + Spring Boot Test + Testcontainers |

---

## Development Commands

```bash
# Run locally with docker (preferred — includes Postgres)
docker compose up

# Build (skip tests)
mvn package -DskipTests

# Run all tests (requires Docker for Testcontainers)
mvn test

# Run concurrency invariant tests only
mvn test -Dtest=ConcurrencyTest

# Run the app locally (requires local Postgres on port 5432)
mvn spring-boot:run

# Check for compilation errors
mvn compile

# Clean build artifacts
mvn clean
```

---

## Project Structure

```
src/main/java/com/wallet/
├── WalletApplication.java        # entrypoint
├── config/                       # Spring beans, filter registration
├── controller/                   # REST handlers (thin — delegate to service)
├── service/                      # Business logic + invariant enforcement
├── repository/                   # JdbcTemplate data access (raw SQL)
├── model/                        # Domain records (Wallet, Transfer, LedgerEntry)
├── dto/                          # Request/Response records with @JsonProperty
├── filter/                       # Servlet filters (auth, correlation ID, logging)
├── metrics/                      # Micrometer counter wrappers
└── exception/                    # Domain exceptions + GlobalExceptionHandler

src/main/resources/
├── application.yml               # Base config
├── application-prod.yml          # Render.com overrides
├── logback-spring.xml            # Structured JSON logging
└── db/migration/                 # Flyway SQL files (V1, V2, V3)
```

---

## CRITICAL FINANCIAL INVARIANTS — NEVER VIOLATE

These are hard constraints. Any code change that could violate these is **wrong by definition**:

### 1. Money is always integer paise — NEVER float
```java
// ✅ CORRECT
long balancePaise = 5000L;

// ❌ WRONG — never do this
double balanceRupees = 50.00;
BigDecimal balance = new BigDecimal("50.00");
```

### 2. Get-or-create wallet uses upsert — NEVER check-then-insert
```java
// ✅ CORRECT — DB enforces uniqueness
jdbc.update("INSERT INTO wallets ... ON CONFLICT (user_id) DO NOTHING", userId);
Wallet w = findByUserId(userId);

// ❌ WRONG — TOCTOU race: two concurrent requests → two wallets
Optional<Wallet> existing = findByUserId(userId);
if (existing.isEmpty()) {
    jdbc.update("INSERT INTO wallets ...", userId);
}
```

### 3. Wallet locks MUST be acquired in sorted UUID order
```java
// ✅ CORRECT — deadlock-proof
UUID lowerId  = id1.compareTo(id2) <= 0 ? id1 : id2;
UUID higherId = id1.compareTo(id2) <= 0 ? id2 : id1;
// Lock lower first, then higher

// ❌ WRONG — A→B and B→A can deadlock
lockWallet(fromId);   // unsorted order
lockWallet(toId);
```

### 4. Idempotency check MUST be in the same transaction as the balance update
```java
// ✅ CORRECT — INSERT transfer row (unique key) + balance UPDATE in one tx
transactionTemplate.execute(status -> {
    transferRepo.insertPending(id, ...);  // unique constraint on idempotency_key
    walletRepo.lockInSortedOrder(...);
    walletRepo.adjustBalance(...);
    ledgerRepo.insertEntry(...);
    transferRepo.updateStatus(id, COMPLETED);
});

// ❌ WRONG — TOCTOU: two concurrent requests both pass the check, both debit
Optional<Transfer> existing = findByIdempotencyKey(key); // separate tx
if (existing.isEmpty()) {
    executeTransfer(...); // second tx — gap between these two = double-spend
}
```

### 5. Ledger entries are append-only — NEVER update or delete
```java
// ✅ CORRECT — always INSERT
ledgerRepo.insertEntry(transferId, walletId, "debit", amount, before, after);

// ❌ WRONG — ledger must be immutable
jdbc.update("UPDATE ledger_entries SET ... WHERE id = ?", ...);
jdbc.update("DELETE FROM ledger_entries WHERE id = ?", ...);
```

### 6. Balance check must happen AFTER acquiring the row lock
```java
// ✅ CORRECT — read balance from locked row
List<Wallet> locked = walletRepo.lockInSortedOrder(fromId, toId);
Wallet sender = findFromList(locked, fromId);
if (sender.balance() < amount) { ... decline ... }

// ❌ WRONG — balance could change between read and lock
long balance = walletRepo.findById(fromId).get().balance(); // unlocked read
lockWallet(fromId);
if (balance < amount) { ... }  // stale balance!
```

---

## Coding Conventions

### General
- Use **Java records** for all immutable domain objects (models, DTOs)
- Use `Optional<T>` for nullable return values from repositories
- Use `@Transactional(readOnly = true)` on all read-only repository methods
- Use `@Transactional` (read-write) only in services, not controllers
- Controllers are **thin** — they delegate to services, handle HTTP concerns only
- Services are **stateless** — all state in the database

### Naming
| Type | Convention | Example |
|---|---|---|
| Controllers | `<Entity>Controller` | `WalletController` |
| Services | `<Entity>Service` | `TransferService` |
| Repositories | `<Entity>Repository` | `WalletRepository` |
| DTOs | `<Action><Entity>Request/Response` | `CreateTransferRequest`, `TransferResponse` |
| Exceptions | `<Condition>Exception` | `InsufficientFundsException` |
| SQL queries | constants in repository class | `private static final String FIND_BY_ID = "..."` |

### API Response Format
All success responses return the resource directly.
All error responses use the `ErrorResponse` record:
```json
{
  "error": "INSUFFICIENT_FUNDS",
  "message": "Sender balance 4999 paise is less than requested 5000 paise",
  "correlation_id": "abc-123"
}
```

### HTTP Status Codes
| Code | When |
|---|---|
| 200 | GET success, idempotent replay |
| 201 | Wallet created, transfer executed |
| 400 | Invalid request body, amount ≤ 0 |
| 401 | Missing/malformed Authorization header |
| 403 | Transferring from another user's wallet |
| 404 | Wallet or transfer not found |
| 409 | Same idempotency key, different request body |
| 422 | Insufficient funds, self-transfer |
| 500 | Unexpected server error |

---

## SQL Conventions

- **Always use parameterized queries** via `JdbcTemplate` — no string concatenation
- **Always use `FOR UPDATE`** when reading rows that will be updated in the same transaction
- **Always lock in ascending UUID order** (see invariant #3 above)
- **Stored balances are BIGINT** — never use `NUMERIC`, `DECIMAL`, or `FLOAT` for money
- **Migration files are immutable** — never edit an existing `V*.sql` file; create a new one

### Row Mapper Pattern
Define `RowMapper` as a `private static final` field in the repository:
```java
private static final RowMapper<Wallet> WALLET_ROW_MAPPER = (rs, rowNum) -> new Wallet(
    UUID.fromString(rs.getString("id")),
    rs.getString("user_id"),
    rs.getLong("balance"),
    rs.getTimestamp("created_at").toInstant(),
    rs.getTimestamp("updated_at").toInstant()
);
```

---

## Logging Standards

> Full logging specification: `docs/logging-standards.md`

**Quick reference:**
- All logs are **structured JSON** via `logstash-logback-encoder`
- Every request has a `correlation_id` (from `X-Correlation-ID` header or generated UUID)
- `correlation_id` and `user_id` are always in **MDC** — they appear in every log line automatically
- Domain events use the format: `<domain>.<action>` (e.g., `transfer.completed`, `wallet.created`)
- Use `log.info("event description", kv("event", "transfer.completed"), kv("transfer_id", id), ...)`
- **NEVER log sensitive data**: no raw card numbers, no passwords, no full request bodies for financial ops

```java
// ✅ CORRECT domain event log
log.info("Transfer completed",
    kv("event", "transfer.completed"),
    kv("transfer_id", transfer.id()),
    kv("from_wallet", transfer.fromWalletId()),
    kv("to_wallet", transfer.toWalletId()),
    kv("amount_paise", transfer.amountPaise()),
    kv("sender_balance_after", senderBalanceAfter)
);

// ❌ WRONG — unstructured, not queryable
log.info("Transfer " + transferId + " completed for " + userId);
```

---

## Git Commit Conventions

Follow **Conventional Commits** format:
```
<type>(<scope>): <short description>

[optional body]
```

Types: `feat`, `fix`, `refactor`, `test`, `docs`, `chore`, `perf`

Examples:
```
feat(wallet): add get-or-create endpoint with upsert race protection
feat(transfer): implement sorted FOR UPDATE locking for conservation invariant
feat(idempotency): commit idempotency key in same tx as ledger movement
fix(transfer): handle DuplicateKeyException for concurrent idempotent replays
docs(adr): add ADR-001 for concurrency mechanism decision
test(concurrency): add multi-threaded conservation and idempotency tests
chore(docker): add multi-stage Dockerfile with non-root user
```

**Commit hygiene:**
- Commit after each logical unit of work (not one giant commit)
- Keep commits small and focused
- Always ensure the build passes before committing (`mvn compile`)

---

## Testing Conventions

- Unit tests: `src/test/java/com/wallet/<Class>Test.java`
- Integration tests: use `@SpringBootTest` + Testcontainers for real Postgres
- Concurrency tests: use `ExecutorService` with `CountDownLatch` to fire concurrent requests
- Test naming: `<method>_<scenario>_<expectedOutcome>` e.g., `executeTransfer_insufficientFunds_returnsDeclined`

```java
// Concurrency test pattern
int threads = 50;
CountDownLatch ready = new CountDownLatch(threads);
CountDownLatch start = new CountDownLatch(1);
ExecutorService pool = Executors.newFixedThreadPool(threads);

for (int i = 0; i < threads; i++) {
    pool.submit(() -> {
        ready.countDown();
        start.await();       // all threads start simultaneously
        // ... test action
    });
}
ready.await();
start.countDown();           // fire all at once
```

---

## AI Assistance Disclosure

This project uses Claude AI (via Antigravity) as a coding assistant.

**I directed (design decisions made by me):**
- All architectural decisions: sorted `FOR UPDATE`, idempotency in same tx, upsert pattern
- Data model design (3 tables, which columns, which indexes, why BIGINT paise)
- What to log, what metrics to capture, dashboard approach
- Why to reject each alternative (SERIALIZABLE, Redis, app-level checks)
- Deployment strategy, observability design

**AI assisted (implementation under my direction):**
- Java boilerplate (records, Spring annotations, JdbcTemplate patterns)
- SQL migration files (I specified schema, AI wrote SQL)
- Dockerfile and docker-compose scaffolding
- Documentation structure and prose

**Every design call is mine. AI typed; I decided.**

---

## Common Pitfalls — Don't Do These

1. **Don't use JPA/Hibernate** — we need explicit `SELECT FOR UPDATE` control
2. **Don't check idempotency in a separate transaction** — TOCTOU race → double-spend
3. **Don't lock wallets in request order** — deadlock guaranteed under A→B + B→A
4. **Don't use `Double` or `float` for amounts** — floating-point rounding errors
5. **Don't read balance before locking** — lost update race
6. **Don't use `@Transactional` in controllers** — service layer owns tx boundaries
7. **Don't use `SELECT *`** — always name columns explicitly for clarity + stability
8. **Don't swallow `DuplicateKeyException`** — it signals an idempotent replay
9. **Don't UPDATE or DELETE ledger_entries** — ledger is immutable by design
10. **Don't add endpoints without idempotency support** — all money-moving ops need it

