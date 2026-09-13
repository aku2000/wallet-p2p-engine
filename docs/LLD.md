# Low-Level System Design (LLD)

## 1. Database Schema & Data Modeling

The schema is partitioned across three core tables, managed via Flyway migrations (`V1`, `V2`, `V3`).

```
┌──────────────────────────────────────────────┐
│                   wallets                    │
├─────────────────┬─────────────┬──────────────┤
│ id              │ UUID (PK)   │ DEFAULT uuid │
│ user_id         │ TEXT (UQ)   │ NOT NULL     │
│ balance         │ BIGINT      │ CHECK >= 0   │
│ created_at      │ TIMESTAMPTZ │ NOT NULL     │
│ updated_at      │ TIMESTAMPTZ │ NOT NULL     │
└────────┬─────────────────────────────┬───────┘
         │ 1                           │ 1
         │                             │
         │ M                           │ M
┌────────▼──────────────────────┐      │
│          transfers            │      │
├─────────────────┬─────────────┤      │
│ id              │ UUID (PK)   │      │
│ from_wallet_id  │ UUID (FK)   │      │
│ to_wallet_id    │ UUID (FK)   │      │
│ amount_paise    │ BIGINT      │      │
│ status          │ TEXT        │      │
│ idempotency_key │ TEXT (UQ)   │      │
│ request_hash    │ TEXT        │      │
│ note            │ TEXT        │      │
│ reversed_by     │ UUID (FK)   │      │
│ created_at      │ TIMESTAMPTZ │      │
│ updated_at      │ TIMESTAMPTZ │      │
└────────┬──────────────────────┘      │
         │ 1                           │
         │                             │
         │ M                           │ M
┌────────▼─────────────────────────────▼───────┐
│                ledger_entries                │
├─────────────────┬─────────────┬──────────────┤
│ id              │ UUID (PK)   │ DEFAULT uuid │
│ transfer_id     │ UUID (FK)   │ NOT NULL     │
│ wallet_id       │ UUID (FK)   │ NOT NULL     │
│ direction       │ TEXT        │ debit/credit │
│ amount_paise    │ BIGINT      │ CHECK > 0    │
│ balance_before  │ BIGINT      │ NOT NULL     │
│ balance_after   │ BIGINT      │ NOT NULL     │
│ created_at      │ TIMESTAMPTZ │ NOT NULL     │
└─────────────────┴─────────────┴──────────────┘
```

### Table Definitions

#### `wallets`
- `id` (UUID PK): Global unique identifier.
- `user_id` (TEXT UNIQUE): User identity string. The unique index `idx_wallets_user_id` serves as the race-guard for concurrent initialization.
- `balance` (BIGINT): Integer amount in paise (1 INR = 100 paise). Protected by `chk_wallets_balance_non_negative CHECK (balance >= 0)`.

#### `transfers`
- `id` (UUID PK): Global unique identifier.
- `from_wallet_id` (UUID FK -> wallets.id): Source wallet.
- `to_wallet_id` (UUID FK -> wallets.id): Target wallet.
- `amount_paise` (BIGINT): Must satisfy `amount_paise > 0`.
- `status` (TEXT): State machine value: `pending`, `completed`, `declined`, `reversed`.
- `idempotency_key` (TEXT UNIQUE): Client-supplied idempotency key. The unique index `idx_transfers_idempotency_key` guarantees exactly-once processing.
- `request_hash` (TEXT): SHA-256 hash of canonical request parameters (`from:to:amount:idempotency_key`) to detect payload tampering with identical keys.
- `reversed_by` (UUID FK -> transfers.id): Future-proof foreign key for financial reversals.

#### `ledger_entries`
- **Append-only journal** of every balance mutation. Never modified or deleted.
- `direction` (TEXT): `debit` (outflow) or `credit` (inflow).
- `balance_before` (BIGINT): Snapshot of wallet balance immediately before mutation.
- `balance_after` (BIGINT): Snapshot of wallet balance immediately after mutation.

---

## 2. Core Invariant Concurrency Mechanisms

### 2.1 Deadlock-Free Sorted Locking Proof

**Problem**: When transfer $T_1$ moves money from Wallet A to Wallet B, and transfer $T_2$ moves money from Wallet B to Wallet A concurrently:
- If $T_1$ locks A first then tries to lock B, while $T_2$ locks B first then tries to lock A:
  $$\text{Cycle: } T_1 \to B \text{ (held by } T_2) \text{ and } T_2 \to A \text{ (held by } T_1) \implies \mathbf{Deadlock}.$$

**Solution**: Deterministic global resource sorting.
In `WalletRepository.lockInSortedOrder(UUID id1, UUID id2)`:
1. Compare UUID lexicographical values:
   $$\text{lowerId} = \min(id_1, id_2), \quad \text{higherId} = \max(id_1, id_2)$$
2. Execute two explicit sequential locking queries:
   ```sql
   SELECT id, user_id, balance FROM wallets WHERE id = lowerId FOR UPDATE;
   SELECT id, user_id, balance FROM wallets WHERE id = higherId FOR UPDATE;
   ```

**Proof**:
- Both $T_1$ ($A \to B$) and $T_2$ ($B \to A$) evaluate $\min(A, B)$ to the exact same UUID.
- The transaction that acquires the lock on $\min(A, B)$ first proceeds; the second transaction blocks on $\min(A, B)$ before acquiring any lock.
- Because no transaction holds a resource while requesting a lower-order resource, Coffman's circular wait condition is broken. **Deadlock is structurally impossible.**

---

### 2.2 Atomic Idempotency Commit Placement

```
Client Request
      │
      ▼
TransferService.executeTransfer()
      │
      ▼
┌────────────────────────── Transaction Boundary ──────────────────────────┐
│ 1. INSERT INTO transfers (..., status='pending')                         │
│    └─► Races on UNIQUE idx_transfers_idempotency_key                     │
│        If key exists in-flight: PostgreSQL blocks until first tx commits │
│        If key already committed: throws DuplicateKeyException            │
│                                                                          │
│ 2. SELECT ... FOR UPDATE (lower UUID)                                    │
│ 3. SELECT ... FOR UPDATE (higher UUID)                                   │
│ 4. Balance check (sender.balance >= amount_paise)                        │
│    ├─► Insufficient: UPDATE status='declined' -> COMMIT -> return 422    │
│    └─► Sufficient:                                                       │
│        5. UPDATE wallets (debit sender)                                  │
│        6. UPDATE wallets (credit receiver)                               │
│        7. INSERT INTO ledger_entries (debit row)                         │
│        8. INSERT INTO ledger_entries (credit row)                        │
│        9. UPDATE transfers status='completed' -> COMMIT                  │
└────────────────────────────────────┬─────────────────────────────────────┘
                                     │
                    Throws DuplicateKeyException
                                     │
                                     ▼
               Outer Catch Block (Transaction Auto-Rolled Back)
                                     │
                 10. SELECT * FROM transfers WHERE key = ?
                                     │
               ┌─────────────────────┴─────────────────────┐
               ▼                                           ▼
      request_hash matches                        request_hash differs
               │                                           │
               ▼                                           ▼
   Return Original Transfer (200 OK)             Throw 409 Conflict
```

**Why Check-Then-Act in Separate Transactions Fails (TOCTOU)**:
If an application checks `SELECT * FROM transfers WHERE key = ?` in Tx 1, and inserts in Tx 2:
- Two concurrent requests both execute Tx 1 simultaneously; both find 0 rows.
- Both proceed to Tx 2 and execute double balance deductions.
- Enforcing uniqueness in the **same transaction** guarantees atomic serialization at the database engine level.

---

## 3. Double-Entry Bookkeeping Example

When user Alice transfers ₹50.00 (5,000 paise) to Bob:

### Initial State
- Alice (`W_A`): 45,000 paise
- Bob (`W_B`): 10,000 paise
- Total: 55,000 paise

### Operations inside Single Transaction
1. Lock `W_A` and `W_B` in UUID order.
2. Check `45,000 >= 5,000` (valid).
3. `UPDATE wallets SET balance = 40000 WHERE id = W_A`
4. `UPDATE wallets SET balance = 15000 WHERE id = W_B`
5. `INSERT INTO ledger_entries`:
   - `wallet_id = W_A, direction = 'debit', amount = 5000, before = 45000, after = 40000`
   - `wallet_id = W_B, direction = 'credit', amount = 5000, before = 10000, after = 15000`

### Final State
- Alice (`W_A`): 40,000 paise
- Bob (`W_B`): 15,000 paise
- Total: 55,000 paise ($\Delta = 0$)

### Periodic Verification Query
```sql
SELECT
    w.id,
    w.balance AS actual_balance,
    COALESCE(SUM(CASE WHEN l.direction = 'credit' THEN l.amount_paise ELSE -l.amount_paise END), 0) AS journal_sum,
    w.balance - COALESCE(SUM(CASE WHEN l.direction = 'credit' THEN l.amount_paise ELSE -l.amount_paise END), 0) AS discrepancy
FROM wallets w
LEFT JOIN ledger_entries l ON l.wallet_id = w.id
GROUP BY w.id, w.balance
HAVING w.balance != COALESCE(SUM(CASE WHEN l.direction = 'credit' THEN l.amount_paise ELSE -l.amount_paise END), 0);
```
*Expected output: Exactly 0 rows.*

---

## 4. Transfer State Machine

```
              ┌───────────────┐
              │    PENDING    │
              └───────┬───────┘
                      │
           ┌──────────┴──────────┐
           ▼                     ▼
    ┌─────────────┐       ┌─────────────┐
    │  COMPLETED  │       │  DECLINED   │
    └──────┬──────┘       └─────────────┘
           │
           │ (R3 Live Reversal)
           ▼
    ┌─────────────┐
    │  REVERSED   │
    └─────────────┘
```

---

## 5. API Error Taxonomy

| Status Code | Error Code | Trigger Condition |
|---|---|---|
| `400 Bad Request` | `INVALID_REQUEST` | Negative/zero amount, non-UUID format, self-transfer. |
| `401 Unauthorized` | `UNAUTHORIZED` | Missing or malformed `Authorization: Bearer <token>` header. |
| `403 Forbidden` | `FORBIDDEN` | Caller attempts to transfer funds out of a wallet they do not own. |
| `404 Not Found` | `NOT_FOUND` | Source or destination wallet UUID does not exist. |
| `409 Conflict` | `IDEMPOTENCY_CONFLICT` | Idempotency key reused with a differing request body. |
| `422 Unprocessable` | `INSUFFICIENT_FUNDS` | Sender balance is strictly less than requested transfer amount. |
| `500 Internal Error` | `INTERNAL_ERROR` | Unhandled database or system exception. |

