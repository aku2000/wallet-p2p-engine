# High-Level System Design (HLD)

## 1. System Overview & Problem Statement

The `wallet-p2p-engine` is a peer-to-peer (P2P) digital wallet backend designed for high-concurrency financial operations. In financial ledger systems, software defects do not simply cause transient UI errors—they directly create or destroy money. 

This engine is engineered from first principles to guarantee four foundational invariants:
1. **Conservation of Money**: The aggregate sum of balances across all wallets is invariant across any transfer: $\sum B_{\text{initial}} = \sum B_{\text{final}}$.
2. **No Overdraft**: A wallet balance must never be negative ($B_w \ge 0, \forall w$). Transfers exceeding available funds are cleanly declined.
3. **Strict Exactly-Once Execution**: Repeated submissions of the same client-supplied idempotency key produce identical results and mutate balances exactly once.
4. **Race-Free Wallet Initialization**: Concurrent creation requests for the same identity yield a single deterministic wallet entity.

---

## 2. Scope

### In Scope
- Wallet lifecycle: deterministic, race-free get-or-create by user identifier.
- P2P transfers: atomic transfer of integer paise between wallets.
- Double-entry audit ledger: immutable append-only journal capturing point-in-time balance snapshots.
- Idempotency management: atomic conflict detection with SHA-256 payload verification.
- Production observability: structured JSON logging with distributed correlation tracking and domain metrics.

### Out of Scope
- External payment gateway integrations (Razorpay, Stripe) and bank top-up / cash-out rails.
- Asynchronous distributed messaging (Kafka/RabbitMQ) and cache layers (Redis).
- Multi-currency conversions and floating-point fractional currency calculations.
- Fraud and AML risk scoring engines.

---

## 3. Requirements

### Functional Requirements
- `POST /wallets`: Initialize or retrieve an existing user wallet.
- `GET /wallets/{id}`: Query wallet balance in integer paise.
- `POST /transfers`: Execute an atomic money movement with `from`, `to`, `amount_paise`, and `idempotency_key`.
- `GET /transfers/{id}`: Inspect transfer status (`completed`, `declined`, `reversed`).
- `GET /health`: Public liveness/readiness probe.
- `GET /dashboard`: Zero-cost real-time HTML operational dashboard.

### Non-Functional Requirements
- **Consistency**: Immediate strong consistency (CP system under CAP classification).
- **Deadlock Prevention**: Deterministic global resource ordering.
- **Auditability**: Point-in-time reconstruction of account states via immutable double-entry records.
- **Zero-Cost Deployment**: Free-tier deployment on cloud infrastructure (Render + Managed PostgreSQL 16).

---

## 4. System Architecture

```
   [ Client / Burst Probes ]
              │
              │ HTTPS (Bearer Token Auth)
              ▼
    ┌──────────────────────────────────────────────┐
    │              wallet-p2p-engine               │
    │                                              │
    │  ┌────────────────────────────────────────┐  │
    │  │ Filters (CorrelationId -> Auth -> Log) │  │
    │  └──────────────────┬─────────────────────┘  │
    │                     ▼                        │
    │  ┌────────────────────────────────────────┐  │
    │  │ REST Controllers (Wallets / Transfers) │  │
    │  └──────────────────┬─────────────────────┘  │
    │                     ▼                        │
    │  ┌────────────────────────────────────────┐  │
    │  │ Domain Services (TransferService)      │  │
    │  │  - Deterministic Lock Ordering         │  │
    │  │  - Programmatic Transaction Control    │  │
    │  │  - Idempotency Catch & Replay          │  │
    │  └──────────────────┬─────────────────────┘  │
    │                     ▼                        │
    │  ┌────────────────────────────────────────┐  │
    │  │ JdbcTemplate Repositories (Raw SQL)    │  │
    │  └──────────────────┬─────────────────────┘  │
    │                     ▼                        │
    │         Hikari Connection Pool               │
    └─────────────────────┼────────────────────────┘
                          │
                          │ PostgreSQL Wire Protocol
                          ▼
    ┌──────────────────────────────────────────────┐
    │        PostgreSQL 16 Storage Engine          │
    │                                              │
    │  ┌──────────────┐      ┌──────────────────┐  │
    │  │   wallets    │◄─────┤    transfers     │  │
    │  └──────┬───────┘      └─────────┬────────┘  │
    │         │                        │           │
    │         │     ┌────────────────┐ │           │
    │         └────►│ ledger_entries │◄┘           │
    │               └────────────────┘             │
    └──────────────────────────────────────────────┘
```

---

## 5. Component Responsibilities

| Component | Class | Responsibility |
|---|---|---|
| **Correlation Filter** | `CorrelationIdFilter` | Assigns or forwards `X-Correlation-ID` and populates SLF4J MDC. |
| **Authentication** | `BearerAuthFilter` | Extracts `user_id` from bearer token; rejects unauthenticated calls. |
| **Logging Filter** | `RequestLoggingFilter` | Emits structured JSON access logs with duration and status code. |
| **Transfer Engine** | `TransferService` | Orchestrates atomic money transfers, locks wallets in sorted order, writes double-entry records, catches duplicate keys. |
| **Wallet Service** | `WalletService` | Manages race-free get-or-create using PostgreSQL `ON CONFLICT DO NOTHING`. |
| **Persistence** | `*Repository` | Executes parameterized raw SQL statements via `JdbcTemplate`. |
| **Metrics** | `WalletMetrics` | Maintains Micrometer counters for completed, declined, and replayed transfers. |

---

## 6. Technology Decisions

| Technology | Chosen | Rationale | Alternatives Rejected |
|---|---|---|---|
| Language | Java 21 LTS | Native virtual threads, strict memory model, rich ecosystem. | Go (less expressive financial frameworks), Node.js (event-loop CPU blocking). |
| Framework | Spring Boot 3.3 (WebMVC) | Synchronous thread-per-request model allows straightforward blocking JDBC transaction control. | Spring WebFlux / Reactive (reactive transactions complicate pessimistic row locks). |
| DB Access | Spring `JdbcTemplate` | Full control over explicit `SELECT ... FOR UPDATE` and lock sequences. | Hibernate / JPA (generates non-deterministic SQL and implicit lock timing). |
| Database | PostgreSQL 16 | Row-level locking primitives, strict constraint enforcement, transactional DDL. | MySQL (gap locks create unpredictable deadlocks), MongoDB (lacks robust cross-document guarantees). |
| Migrations | Flyway | Immutable, versioned SQL migration scripts applied at application boot. | Liquibase (unnecessary XML/YAML complexity). |

---

## 7. Consistency vs. Availability (CAP Theorem)

In a distributed financial system, the CAP theorem mandates a choice between **Consistency** and **Availability** during network partitions:
- We choose **Strong Consistency (CP)**.
- Under any database failure or connection partition, incoming transfer requests fail immediately rather than proceeding against stale caches or asynchronous queues.
- **Why**: An unavailable payment service results in a transient retry error. An inconsistent payment service results in double-spending, irreversible negative balances, and legal/regulatory liability.

---

## 8. Back-of-the-Envelope Capacity Planning

| Metric | Estimated Scale | Architectural Implication |
|---|---|---|
| Peak Transfer Throughput | 250 TPS | Handled comfortably by a single PostgreSQL instance with indexed row locks. |
| Storage per Transfer | ~500 bytes (1 transfer + 2 ledger entries) | 100,000 daily transfers = 50 MB / day (~18 GB / year). |
| Lock Duration | < 5 milliseconds | Two index-based row locks held strictly during memory updates. |
| Connection Pool | 20 connections | Minimizes PostgreSQL backend contention while sustaining concurrency. |

