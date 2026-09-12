# ADR-002: Idempotency Key Enforcement and Commit Placement

## Status
Accepted

## Context
Network timeouts, client retry storms, and transient gateway failures inevitably cause duplicate HTTP requests for the same transfer. The engine must guarantee that:
1. Re-executing a request with the same `idempotency_key` applies the debit/credit strictly once.
2. Concurrent duplicate requests during a retry storm do not execute parallel balance mutations.
3. A client attempting to reuse an existing idempotency key with different transfer parameters receives HTTP 409 Conflict.

## Decision
We enforce idempotency through a **database-level unique constraint (`idx_transfers_idempotency_key`) committed inside the exact same database transaction that performs the balance updates and writes the ledger entries**.

To detect payload tampering or unintended key collisions, we compute a SHA-256 hash of the canonical request tuple (`from:to:amount_paise:idempotency_key`) and persist it in `transfers.request_hash`.

### Concurrency Flow:
1. At the beginning of the transaction, execute:
   ```sql
   INSERT INTO transfers (id, from_wallet_id, to_wallet_id, amount_paise, status, idempotency_key, request_hash, note)
   VALUES (?, ?, ?, ?, 'pending', ?, ?, ?);
   ```
2. If another concurrent transaction is executing the same key, PostgreSQL's index engine **blocks** the second transaction until the first commits or aborts.
3. If the first transaction commits, the second transaction is immediately aborted by PostgreSQL with a `DuplicateKeyException`.
4. The application catches `DuplicateKeyException` **outside** the transaction boundary, re-reads the committed transfer in a new read-only query, compares the `request_hash`, and returns the original transfer entity.

## Alternatives Considered & Rejected

### 1. Pre-Check in a Separate Transaction (TOCTOU)
- **Mechanism**: Execute `SELECT * FROM transfers WHERE idempotency_key = ?` in Transaction A. If absent, execute money movement in Transaction B.
- **Why Rejected**: Creates a catastrophic Time-Of-Check to Time-Of-Use (TOCTOU) race condition. In a burst of 30 concurrent requests with the same key, all 30 threads check Transaction A simultaneously, all observe zero records, and all 30 proceed to debit the wallet 30 times.

### 2. Standalone In-Memory or Redis Key-Value Store
- **Mechanism**: Store idempotency keys in Redis with `SET NX EX`.
- **Why Rejected**: Dual-write hazard. If Redis acknowledges the key but PostgreSQL crashes before committing the ledger, the client retry is blocked forever thinking the request succeeded. Conversely, if PostgreSQL commits but Redis fails to persist the key, a retry creates a duplicate transfer. Managing distributed 2-phase commit across Redis and PostgreSQL adds fragility for no benefit.

## Consequences
- **Positive**: Zero possibility of duplicate balance mutations under concurrent retry storms; zero dual-write inconsistencies; clean detection of payload divergence (`409 Conflict`).
- **Negative**: The unique index check holds an index row lock during the short duration of the transfer transaction.
