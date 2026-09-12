# Burst Testing & Verification Runbook

This guide details how to reproduce and verify all four financial invariants using the automated burst scripts against any local or deployed environment.

---

## Prerequisites

The verification scripts require standard POSIX CLI utilities:
- `bash` (v4+)
- `curl`
- `xargs`
- `grep`, `awk`, `uniq`

---

## 1. Gate 1: Race-Free Wallet Get-or-Create

Fires 50 concurrent `POST /wallets` requests for a freshly generated user identity using parallel background workers.

### Command
```bash
./scripts/burst_get_or_create.sh [BASE_URL]

# Example against local container:
./scripts/burst_get_or_create.sh http://localhost:8080

# Example against Render live deployment:
./scripts/burst_get_or_create.sh https://wallet-p2p-engine.onrender.com
```

### Expected Output
- All 50 requests return HTTP `201 Created`.
- Distinct wallet IDs returned: **Exactly 1**.
- Script exits with code `0`: `✅ GATE 1 PASSED: Exactly 1 wallet created across 50 concurrent calls.`

---

## 2. Gate 2: Idempotent Exactly-Once Transfer Storm

Fires 30 concurrent `POST /transfers` requests with the **identical** payload and `idempotency_key`, followed by a conflict verification probe with a modified body.

### Command
```bash
./scripts/burst_idempotency.sh [BASE_URL]

# Example:
./scripts/burst_idempotency.sh http://localhost:8080
```

### Expected Output
- Exactly one debit and credit applied to wallet balances.
- All 30 concurrent requests return the identical transfer ID and status.
- Distinct transfer ID count: **Exactly 1**.
- Submitting the same idempotency key with a differing payload returns HTTP `409 Conflict`.
- Script exits with code `0`: `✅ GATE 2 PASSED: Exactly-once transfer invariant verified.`

---

## 3. Gate 3: Conservation & No-Overdraft Under Contention

Seeds three wallets (A, B, C) and fires 50 concurrent cross-transfers touching the same wallets simultaneously in opposite directions ($A \to B$ and $B \to A$), alongside overdrawing debit attempts.

### Command
```bash
./scripts/burst_conservation.sh [BASE_URL]

# Example:
./scripts/burst_conservation.sh http://localhost:8080
```

### Expected Output
- `Initial Total Sum == Final Total Sum`: Zero paise created or lost across the entire system.
- `All Balances >= 0`: No wallet overdrawn.
- `HTTP 500 Count: 0`: No database deadlocks under bidirectional contention ($A \to B$ and $B \to A$).
- Script exits with code `0`: `✅ GATE 3 PASSED: Conservation, no-overdraft, and deadlock-free contention all verified!`
