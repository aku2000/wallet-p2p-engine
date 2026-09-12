# Design Decisions & Invariant Architecture (One-Page Write-Up)

## 1. Data Model & Key Design Choices

The persistence model consists of three relational tables engineered for financial correctness:
- **`wallets`**: Stores `id` (UUID), `user_id` (TEXT with UNIQUE index), and `balance` (BIGINT). Balance is stored as **integer paise** (never floating-point or decimal) protected by a database constraint `CHECK (balance >= 0)`.
- **`transfers`**: Records every money movement with `from_wallet_id`, `to_wallet_id`, `amount_paise`, `status` (`pending`, `completed`, `declined`, `reversed`), and `idempotency_key` (TEXT with UNIQUE index). Includes `request_hash` (SHA-256) for conflict detection and `reversed_by` FK for refund auditability.
- **`ledger_entries`**: An append-only double-entry audit journal. Every completed transfer creates exactly two immutable entries: a debit for the sender and a credit for the receiver. Each entry records `balance_before` and `balance_after`, allowing point-in-time state reconstruction.

---

## 2. The Simplest-Correct Mechanism for Conservation & No-Overdraft

We use **pessimistic row-level locking via `SELECT ... FOR UPDATE` with a deterministic ascending UUID sort order** across two explicit queries within a single transaction:
```java
UUID lowerId  = id1.compareTo(id2) <= 0 ? id1 : id2;
UUID higherId = id1.compareTo(id2) <= 0 ? id2 : id1;
Wallet low  = jdbc.queryForObject("SELECT ... WHERE id = ? FOR UPDATE", ..., lowerId);
Wallet high = jdbc.queryForObject("SELECT ... WHERE id = ? FOR UPDATE", ..., higherId);
```

### Deadlock Elimination Proof
When concurrent transfers touch the same two wallets in opposite directions ($A \to B$ and $B \to A$), both execution paths evaluate $\min(A, B)$ to the exact same lower UUID. Both transactions attempt to acquire the lock on $\min(A, B)$ first. One succeeds; the second blocks *before* acquiring any lock. Coffman's circular wait condition is structurally impossible.

### Heavier Alternatives Rejected
1. **Serializable Isolation (`ISOLATION_SERIALIZABLE`)**: Detects conflicts retrospectively and throws serialization failures (`40001`), requiring application-level retry loops with exponential backoff. Under burst contention, retry storms degrade latency and throughput.
2. **Conditional `UPDATE ... WHERE balance >= amount`**: While correct for single-row debits, a P2P transfer modifies two separate wallets and writes two ledger rows. It cannot inspect both balances atomically before mutation without locking both rows.
3. **Application-Level Read-Check-Write**: Suffers from classic lost-update concurrency races, allowing concurrent double-spends.
4. **Redis Distributed Locks**: Introduces distributed systems failure modes (clock drift, split-brain, network hops) for no benefit over PostgreSQL's native ACID row locks.

---

## 3. Where Idempotency Lives

Idempotency uniqueness is enforced via a unique database index on `transfers.idempotency_key` committed **inside the exact same transaction as the wallet balance adjustments and ledger journal entries**.

- **Why a separate pre-check fails**: Checking key existence in a separate prior transaction introduces a Time-Of-Check to Time-Of-Use (TOCTOU) gap. Under a 30-request concurrent retry storm, all 30 threads observe "key does not exist" and proceed to execute 30 debits.
- **Conflict Handling**: When a duplicate key is submitted, the initial `INSERT INTO transfers` blocks or throws `DuplicateKeyException`. Spring rolls back the transaction. The outer catch re-reads the committed transfer and compares its SHA-256 `request_hash`. Matching payloads return the cached result (`200 OK`); divergent payloads return `409 Conflict`.

---

## 4. Consistency vs. Availability for a Money Workload

We deliberately choose **Strong Consistency (CP)** over high availability. 
- During network partitions or database failover windows, incoming transfers fail fast with HTTP 500/503 errors.
- **What we give up**: 100% continuous uptime during infrastructure degradation.
- **Justification**: In financial systems, unavailability causes a transient, retriable error. Inconsistency causes irreversible balance corruption and double-spending.

---

## 5. AI Directed-vs-Decided Disclosure

- **Directed by Engineer (Architectural Decisions)**: Selected Java 21 / Spring Boot with `JdbcTemplate` (rejected JPA/Hibernate); designed deterministic ascending UUID lock sorting for deadlock elimination; mandated atomic idempotency commit in the same transaction as ledger journal writes; specified integer paise and double-entry schema with balance snapshots; authored burst verification scenarios; decided CP over AP.
- **Decided/Assisted by AI (Implementation)**: Generated boilerplate Java records, DTOs, and Spring filter wiring; drafted standard Maven POM and Docker multi-stage configuration; scaffolded initial documentation markdown structures.

---

## 6. Free-Tier Cost Note

Total infrastructure spend: **₹0 / $0.00**.
- Deployed on **Render.com** (Free Web Service tier running containerized Spring Boot 21).
- Database hosted on **Render Managed PostgreSQL 16** (Free tier, 1 GB storage, 20 max connections).
- Observability provided by built-in `/dashboard` HTML endpoint and `/actuator/metrics` with zero external SaaS fees.
