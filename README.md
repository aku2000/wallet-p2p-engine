# wallet-p2p-engine

A high-concurrency peer-to-peer (P2P) digital wallet engine built for absolute correctness under adversarial contention and network failure.

[![Java](https://img.shields.io/badge/Java-21%20LTS-orange.svg)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3.4-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-blue.svg)](https://www.postgresql.org/)
[![License](https://img.shields.io/badge/License-Apache%202.0-lightgrey.svg)](LICENSE)

---

## Financial Invariants Guaranteed

1. **Conservation of Money**: Sum of all balances is strictly invariant across transfers ($\Delta \sum B = 0$). No money is created or destroyed.
2. **No Overdraft**: A wallet balance never goes negative ($B \ge 0$). Debit attempts exceeding balance are cleanly declined (`422 Unprocessable Entity`).
3. **Strict Exactly-Once Processing**: Same `idempotency_key` guarantees exactly one balance adjustment. Retries return cached responses; same key with altered payload yields `409 Conflict`.
4. **Race-Free Initialization**: Concurrent `POST /wallets` for the same user identity yield a single deterministic wallet record.

---

## Quick Start (Local Docker Compose)

Bring up the complete system (App + PostgreSQL 16) with a single command:

```bash
docker compose up --build
```

- **Application Health**: [http://localhost:8080/health](http://localhost:8080/health)
- **Live Metrics Dashboard**: [http://localhost:8080/dashboard](http://localhost:8080/dashboard)
- **Prometheus Metrics**: [http://localhost:8080/actuator/prometheus](http://localhost:8080/actuator/prometheus)

---

## Automated Burst Verification Probes

Run the verification scripts to probe all three hard gates under live concurrency:

```bash
# Gate 1: Race-free wallet get-or-create (50 concurrent requests)
./scripts/burst_get_or_create.sh http://localhost:8080

# Gate 2: Idempotent transfer storm (30 concurrent same-key requests + 409 conflict probe)
./scripts/burst_idempotency.sh http://localhost:8080

# Gate 3: Conservation & no-overdraft under cross-contention (A->B & B->A deadlock probe)
./scripts/burst_conservation.sh http://localhost:8080
```

---

## API Quick Reference

All monetary quantities are integers in **paise** (1 INR = 100 paise). Authenticate using `Authorization: Bearer <user_id>`.

| Endpoint | Method | Description | Success Code |
|---|---|---|---|
| `/wallets` | `POST` | Get-or-create wallet for caller | `201 Created` |
| `/wallets/{id}` | `GET` | Retrieve wallet balance | `200 OK` |
| `/transfers` | `POST` | Execute atomic money transfer | `201 Created` / `200 OK` |
| `/transfers/{id}` | `GET` | Query transfer status | `200 OK` |
| `/health` | `GET` | Public liveness & readiness check | `200 OK` |
| `/dashboard` | `GET` | Zero-cost HTML real-time operational dashboard | `200 OK` |

---

## Architecture & Technical Documentation

- **[High-Level Design (HLD)](docs/HLD.md)**: System architecture, component responsibilities, CAP theorem trade-offs, and capacity estimates.
- **[Low-Level Design (LLD)](docs/LLD.md)**: Database schema, deadlock-free sorted locking proof, double-entry ledger mechanics, and state machine.
- **[Design Decisions One-Pager](docs/design-decisions.md)**: Core rationale, rejected heavier alternatives, idempotency placement, and AI disclosure.
- **[Architecture Decision Records](docs/decisions/)**:
  - [ADR-001: Concurrency Mechanism](docs/decisions/ADR-001-concurrency-mechanism.md) (Sorted `SELECT FOR UPDATE` vs. Serializable / Redis)
  - [ADR-002: Idempotency Placement](docs/decisions/ADR-002-idempotency-placement.md) (Same-transaction commit vs. TOCTOU pre-check)
  - [ADR-003: Wallet Get-or-Create](docs/decisions/ADR-003-get-or-create-strategy.md) (`ON CONFLICT DO NOTHING` vs. check-then-insert)
  - [ADR-004: Consistency vs Availability](docs/decisions/ADR-004-consistency-vs-availability.md) (Strong consistency CP for financial ledgers)
- **[API Contract](docs/api-contract.md)** & **[OpenAPI 3.0 Specification](docs/openapi.yaml)**
- **[Observability & Logging Standards](docs/observability.md)** & **[Logging Standards](docs/logging-standards.md)**
- **[Burst Testing Runbook](docs/burst-testing.md)**
- **[CLAUDE.md](CLAUDE.md)**: Project conventions, financial invariants, and developer guidelines.
