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

### 2. Check Wallet Balance
```bash
curl -i https://wallet-p2p-engine.onrender.com/wallets/<wallet_uuid> \
  -H "Authorization: Bearer alice"
```

### 3. Execute Transfer
```bash
curl -i -X POST https://wallet-p2p-engine.onrender.com/transfers \
  -H "Authorization: Bearer alice" \
  -H "Content-Type: application/json" \
  -d '{
    "from": "<alice_wallet_uuid>",
    "to": "<bob_wallet_uuid>",
    "amount_paise": 1000,
    "idempotency_key": "custom-tx-key-001",
    "note": "P2P transfer test"
  }'
```
*Responses:*
- `HTTP 201 Created` — newly completed transfer.
- `HTTP 422 Unprocessable Entity` — transfer declined (insufficient balance).
- `HTTP 409 Conflict` — same `idempotency_key` reused with a modified request body.

---

## 4. Key Architectural Highlights

| Invariant / Requirement | Mechanism | Rationale |
| :--- | :--- | :--- |
| **Race-Free Get-or-Create** | `INSERT ... ON CONFLICT (user_id) DO NOTHING` | Database UNIQUE constraint eliminates TOCTOU races. |
| **Exactly-Once Transfer** | `UNIQUE (idempotency_key)` committed inside transaction | Atomic money movement and deduplication; zero partial writes. |
| **Deadlock Elimination** | Deterministic Ascending UUID `SELECT ... FOR UPDATE` | Locking lower UUID first prevents circular wait under bidirectional crosses ($A \leftrightarrow B$). |
| **Double-Entry Ledger** | Append-only `ledger_entries` table | Every transfer creates immutable debit and credit snapshot rows with `balance_before` & `balance_after`. |
| **Money Representation** | `BIGINT` integer paise | Zero floating-point rounding errors. |
