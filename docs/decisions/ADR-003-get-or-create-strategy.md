# ADR-003: Race-Free Wallet Get-or-Create Strategy

## Status
Accepted

## Context
When a client or onboarding workflow initiates multiple concurrent `POST /wallets` requests for the same `user_id` (e.g., parallel user setup calls or rapid mobile app retries), the engine must guarantee that exactly one wallet entity is created, and all concurrent calls receive that identical wallet ID.

## Decision
We implement atomic get-or-create utilizing PostgreSQL's native upsert primitive combined with a unique constraint:
```sql
CREATE UNIQUE INDEX idx_wallets_user_id ON wallets (user_id);
```

In `WalletRepository.upsertByUserId(String userId)`:
```sql
INSERT INTO wallets (id, user_id, balance) 
VALUES (gen_random_uuid(), ?, 0) 
ON CONFLICT (user_id) DO NOTHING;
```
Followed immediately by:
```sql
SELECT id, user_id, balance, created_at, updated_at 
FROM wallets 
WHERE user_id = ?;
```

## Alternatives Considered & Rejected

### 1. Application-Level Check-Then-Insert
- **Mechanism**:
  ```java
  Optional<Wallet> existing = findByUserId(userId);
  if (existing.isEmpty()) {
      insertWallet(userId);
  }
  ```
- **Why Rejected**: Subject to a severe TOCTOU race condition. When 50 concurrent requests arrive simultaneously for a new user:
  - All 50 threads query `findByUserId` and observe empty results.
  - All 50 threads execute `insertWallet`.
  - Without a unique constraint, 50 duplicate wallets are created for one user.
  - With a unique constraint, 49 requests crash with uncaught database unique violation errors (HTTP 500), failing the live probe.

### 2. Pessimistic Table-Level or Advisory Locks
- **Mechanism**: Acquire `pg_advisory_xact_lock(hashtext(userId))` prior to creation.
- **Why Rejected**: Advisory locks introduce extra round-trips and risk resource exhaustion if hash collisions occur. The native `ON CONFLICT DO NOTHING` statement achieves atomic insertion directly inside the database query planner.

## Consequences
- **Positive**: 100% race-free wallet initialization; zero HTTP 500 errors under concurrent initialization storms; deterministic single-record guarantee enforced at storage layer.
- **Negative**: Requires a second `SELECT` query if the row already existed (sub-millisecond execution via indexed lookup).
