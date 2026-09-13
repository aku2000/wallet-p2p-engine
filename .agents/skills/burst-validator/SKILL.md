---
name: burst-validator
description: Automatically runs and verifies the three financial invariant burst tests (Gate 1 race-free create, Gate 2 idempotency storm, Gate 3 conservation under contention) against a live deployed URL or local server.
---

# burst-validator Skill

Autonomous agentic skill for validating financial invariants against a running `wallet-p2p-engine` instance under extreme concurrency.

## Capability

When invoked by an AI coding agent or human evaluator, this skill executes the live probe scripts, captures standard output, evaluates status code distributions, and verifies mathematical invariants:
1. **Gate 1 (Race-Free Create)**: 50 concurrent wallet creations $\to$ exactly 1 wallet entity.
2. **Gate 2 (Exactly-Once Transfer)**: 30 concurrent requests with same idempotency key $\to$ exactly 1 debit/credit; 409 Conflict on payload tampering.
3. **Gate 3 (Conservation Under Contention)**: 50 concurrent cross-transfers ($A \to B$, $B \to A$, $B \to C$, $C \to A$, plus overdrafts) $\to$ zero money created/destroyed, zero deadlocks (zero HTTP 500s), zero negative balances.

## Execution Procedure

Run the validation suite by passing the target base URL:

```bash
# Set target base URL (local container or Render live deployment)
TARGET_URL="${1:-http://localhost:8080}"

echo "=========================================================="
echo "Running Financial Invariant Validation Suite against: $TARGET_URL"
echo "=========================================================="

# 1. Probe Gate 1: Race-Free Get-or-Create
bash scripts/burst_get_or_create.sh "$TARGET_URL"

# 2. Probe Gate 2: Idempotent Transfer Storm
bash scripts/burst_idempotency.sh "$TARGET_URL"

# 3. Probe Gate 3: Conservation & No-Overdraft
bash scripts/burst_conservation.sh "$TARGET_URL"

echo "=========================================================="
echo "All financial invariants verified successfully!"
echo "=========================================================="
```

## How to Demonstrate During Interviews

You can instruct your AI assistant in chat:
> *"Run the burst-validator skill against https://wallet-p2p-engine.onrender.com"*

The agent will execute all three gates sequentially, stream the terminal verification output, and print a consolidated validation report showing that no money was created or destroyed and all invariants held.

