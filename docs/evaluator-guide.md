# Evaluator & Reviewer Quickstart Guide

This guide is designed for reviewers to inspect, verify, and test the **Digital Wallet & P2P Transfer Engine** deployed live on cloud infrastructure.

- **Live Base URL:** `https://wallet-p2p-engine.onrender.com`
- **GitHub Repository:** `https://github.com/aku2000/wallet-p2p-engine`
- **Real-Time Live Dashboard:** `https://wallet-p2p-engine.onrender.com/dashboard`
- **Liveness Probe:** `https://wallet-p2p-engine.onrender.com/health`

---

## 1. How to Test the Financial Burst Scenarios

The engine is evaluated on three core financial invariants under concurrency. You can test them in three different ways:

### Option A: Using an AI Coding Agent (Cursor, Claude Code, Antigravity)
If you are evaluating within an agent-enabled environment, the repository includes a standardized agent skill at [`.agents/skills/burst-validator/SKILL.md`](../.agents/skills/burst-validator/SKILL.md).  
Simply ask your agent:
> *"Run the burst-validator skill against https://wallet-p2p-engine.onrender.com"*

The agent will sequentially run all 3 gates and report a mathematical verification summary.

---

### Option B: One-Command Terminal Run (No AI Required)
Clone the repository and execute all three burst tests directly from any terminal (macOS / Linux):

```bash
# Clone and enter repo
git clone https://github.com/aku2000/wallet-p2p-engine.git
cd wallet-p2p-engine

# Run all 3 gates against the live deployment in one line:
for s in scripts/burst_*.sh; do bash "$s" https://wallet-p2p-engine.onrender.com; done
```

Or run individual gates:
```bash
# Gate 1: Race-free create (50 concurrent POST /wallets for same user -> exactly 1 wallet)
./scripts/burst_get_or_create.sh https://wallet-p2p-engine.onrender.com

# Gate 2: Exactly-once storm (30 concurrent transfers with same key -> 1 debit, 409 on altered body)
./scripts/burst_idempotency.sh https://wallet-p2p-engine.onrender.com

# Gate 3: Conservation & deadlock probe (50 concurrent cross-transfers A->B, B->A, overdrafts)
./scripts/burst_conservation.sh https://wallet-p2p-engine.onrender.com
```

---

## 2. Live Dashboard & Structured Logs

### Real-Time Web Dashboard
Visit: **[https://wallet-p2p-engine.onrender.com/dashboard](https://wallet-p2p-engine.onrender.com/dashboard)**  
- Auto-refreshes every 5 seconds.
- Displays live JVM counters: **Completed Transfers**, **Declined Transfers**, **Idempotent Replays**, and **Wallets Created**.

### Structured JSON Logs
- Every request emits structured JSON to `STDOUT` via `logstash-logback-encoder`.
- Includes `correlation_id`, `user_id`, `event` (`wallet.created`, `transfer.completed`, `transfer.declined`, `transfer.idempotent_replay`), and duration.
- **Sample Log Dump:** A sample of actual structured JSON logs captured during live burst runs is committed in the repository at [`docs/logs-sample.json`](logs-sample.json).
- **Raw Prometheus Stream:** Live metrics are available at [`/actuator/prometheus`](https://wallet-p2p-engine.onrender.com/actuator/prometheus).

---

## 3. Manual API Testing (cURL Reference)

### Authentication & Headers
- **Auth:** Standard Bearer token header: `-H "Authorization: Bearer <username>"`. Per spec, the token **is** the user identity (no JWT setup or password required). You can use any name (e.g. `alice`, `bob`).
- **Correlation ID:** **Optional**. If omitted, the server automatically generates a UUID, attaches it to MDC logs, and returns it in the `X-Correlation-ID` response header.

### 1. Create / Get Wallet
```bash
curl -i -X POST https://wallet-p2p-engine.onrender.com/wallets \
  -H "Authorization: Bearer alice"
```
*Response: HTTP 201 with wallet UUID and initial balance (0).*

---

### 2. Check Wallet Balance
**Template:**
```bash
curl -i https://wallet-p2p-engine.onrender.com/wallets/<wallet_uuid> \
  -H "Authorization: Bearer <username>"
```

**Ready-to-Run Live Sample (Active Pre-Seeded Account):**
```bash
curl -i https://wallet-p2p-engine.onrender.com/wallets/71e66003-203d-4425-8a9f-5108e508969f \
  -H "Authorization: Bearer user-retry-a"
```
*Returns real live balance (~49,200 paise / ₹492.00).*

---

### 3. Execute Transfer
**Template:**
```bash
curl -i -X POST https://wallet-p2p-engine.onrender.com/transfers \
  -H "Authorization: Bearer <sender_username>" \
  -H "Content-Type: application/json" \
  -d '{
    "from": "<sender_wallet_uuid>",
    "to": "<recipient_wallet_uuid>",
    "amount_paise": 100,
    "idempotency_key": "unique-client-key-'$RANDOM'",
    "note": "P2P transfer test"
  }'
```

**Ready-to-Run Live Sample (Sends ₹1.00 from user-retry-a to user-retry-b):**
```bash
curl -i -X POST https://wallet-p2p-engine.onrender.com/transfers \
  -H "Authorization: Bearer user-retry-a" \
  -H "Content-Type: application/json" \
  -d '{
    "from": "71e66003-203d-4425-8a9f-5108e508969f",
    "to": "bb332fd2-a223-4d9c-9a36-2c8fea301eb8",
    "amount_paise": 100,
    "idempotency_key": "live-demo-key-1",
    "note": "Evaluator live demo"
  }'
```
*Behavior on first run: `HTTP 201 Created` with transfer ID.*  
*Behavior on resend with same key: `HTTP 201 Created` with identical transfer ID (idempotent replay, zero duplicate deduction).*  
*Behavior on resend with same key but altered amount (e.g. `999`): `HTTP 409 Conflict`.*

---

### 4. Overdraft Test (Immediate Declination)
**Ready-to-Run Live Sample (Attempts to send ₹100,000 when balance is ~₹492):**
```bash
curl -i -X POST https://wallet-p2p-engine.onrender.com/transfers \
  -H "Authorization: Bearer user-retry-a" \
  -H "Content-Type: application/json" \
  -d '{
    "from": "71e66003-203d-4425-8a9f-5108e508969f",
    "to": "bb332fd2-a223-4d9c-9a36-2c8fea301eb8",
    "amount_paise": 10000000,
    "idempotency_key": "overdraft-demo-'$RANDOM'"
  }'
```
*Returns `HTTP 422 Unprocessable Entity` (`status: declined`, `reason: insufficient_funds`).*

---

## 4. Key Architectural Highlights

| Invariant / Requirement | Mechanism | Rationale |
| :--- | :--- | :--- |
| **Race-Free Get-or-Create** | `INSERT ... ON CONFLICT (user_id) DO NOTHING` | Database UNIQUE constraint eliminates TOCTOU races. |
| **Exactly-Once Transfer** | `UNIQUE (idempotency_key)` committed inside transaction | Atomic money movement and deduplication; zero partial writes. |
| **Deadlock Elimination** | Deterministic Ascending UUID `SELECT ... FOR UPDATE` | Locking lower UUID first prevents circular wait under bidirectional crosses ($A \leftrightarrow B$). |
| **Double-Entry Ledger** | Append-only `ledger_entries` table | Every transfer creates immutable debit and credit snapshot rows with `balance_before` & `balance_after`. |
| **Money Representation** | `BIGINT` integer paise | Zero floating-point rounding errors. |

