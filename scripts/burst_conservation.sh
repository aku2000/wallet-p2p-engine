#!/usr/bin/env bash
# ==============================================================================
# Gate 3 Live Probe: Conservation + No-Overdraft Under Contention
#
# Seeds 3 wallets. Fires 50 concurrent cross-transfers including:
#   - A->B and B->A simultaneously (deadlock probe)
#   - Overdraft attempts (huge amounts)
# Expects:
#   - Sum of all balances unchanged (Conservation)
#   - No negative balances (No Overdraft)
#   - Zero HTTP 500 errors (No Deadlock)
#
# Usage:
#   ./scripts/burst_conservation.sh [BASE_URL] [WALLET_A] [USER_A] [WALLET_B] [USER_B] [WALLET_C] [USER_C]
# ==============================================================================
# NOTE: no set -e here — subshells must handle errors individually
set -uo pipefail

BASE_URL="${1:-http://localhost:8080}"
OUT_DIR=$(mktemp -d)
trap 'rm -rf "$OUT_DIR"' EXIT

echo "================================================================="
echo "⚡ GATE 3 BURST: Conservation & No-Overdraft Under Contention"
echo "  Target URL: $BASE_URL"
echo "================================================================="

# ── Wallet Setup ──────────────────────────────────────────────────────────────
if [ -z "${2:-}" ]; then
    echo "Creating 3 test wallets..."
    USER_A="alice-conserve-$(date +%s)"
    USER_B="bob-conserve-$(date +%s)"
    USER_C="charlie-conserve-$(date +%s)"

    RESP_A=$(curl -sf -X POST "$BASE_URL/wallets" -H "Authorization: Bearer $USER_A" || echo '{}')
    RESP_B=$(curl -sf -X POST "$BASE_URL/wallets" -H "Authorization: Bearer $USER_B" || echo '{}')
    RESP_C=$(curl -sf -X POST "$BASE_URL/wallets" -H "Authorization: Bearer $USER_C" || echo '{}')

    WALLET_A=$(printf '%s' "$RESP_A" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
    WALLET_B=$(printf '%s' "$RESP_B" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
    WALLET_C=$(printf '%s' "$RESP_C" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

    # Auto-seed funds if seed_wallet.py is present
    SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
    if [ -f "$SCRIPT_DIR/seed_wallet.py" ]; then
        PYTHON_BIN="python3"
        [ -f "/tmp/wallet-venv/bin/python3" ] && PYTHON_BIN="/tmp/wallet-venv/bin/python3"
        echo "Seeding test wallets with initial funds (100,000 paise each)..."
        "$PYTHON_BIN" "$SCRIPT_DIR/seed_wallet.py" "$USER_A" 100000 >/dev/null 2>&1 || true
        "$PYTHON_BIN" "$SCRIPT_DIR/seed_wallet.py" "$USER_B" 100000 >/dev/null 2>&1 || true
        "$PYTHON_BIN" "$SCRIPT_DIR/seed_wallet.py" "$USER_C" 100000 >/dev/null 2>&1 || true
    fi
else
    WALLET_A="$2"; USER_A="${3:-alice}"
    WALLET_B="$4"; USER_B="${5:-bob}"
    WALLET_C="$6"; USER_C="${7:-charlie}"
fi

if [ -z "$WALLET_A" ] || [ -z "$WALLET_B" ] || [ -z "$WALLET_C" ]; then
    echo "❌ Could not create wallets. Check the server is up."
    exit 1
fi

# ── Fetch balance helper ──────────────────────────────────────────────────────
fetch_bal() {
    local wid="$1"
    local u="$2"
    local res
    res=$(curl -sf "$BASE_URL/wallets/$wid" -H "Authorization: Bearer $u" 2>/dev/null || echo '{"balance":0}')
    printf '%s' "$res" | grep -o '"balance":[0-9]*' | cut -d: -f2 || echo 0
}

BAL_A_INIT=$(fetch_bal "$WALLET_A" "$USER_A")
BAL_B_INIT=$(fetch_bal "$WALLET_B" "$USER_B")
BAL_C_INIT=$(fetch_bal "$WALLET_C" "$USER_C")
SUM_INIT=$((BAL_A_INIT + BAL_B_INIT + BAL_C_INIT))

echo ""
echo "Initial Balances:"
echo "  Wallet A ($USER_A): $BAL_A_INIT paise"
echo "  Wallet B ($USER_B): $BAL_B_INIT paise"
echo "  Wallet C ($USER_C): $BAL_C_INIT paise"
echo "  Total:              $SUM_INIT paise"
echo ""

# ── Build transfer task list ──────────────────────────────────────────────────
CONCURRENCY=50
CMD_FILE="$OUT_DIR/commands.txt"
> "$CMD_FILE"

for i in $(seq 1 "$CONCURRENCY"); do
    KEY="contention-$(date +%s)-$RANDOM-$i"
    MOD=$((i % 5))
    case $MOD in
        0) echo "$BASE_URL $USER_A $WALLET_A $WALLET_B 500 $KEY" >> "$CMD_FILE" ;;   # A->B
        1) echo "$BASE_URL $USER_B $WALLET_B $WALLET_A 300 $KEY" >> "$CMD_FILE" ;;   # B->A (deadlock probe)
        2) echo "$BASE_URL $USER_B $WALLET_B $WALLET_C 200 $KEY" >> "$CMD_FILE" ;;   # B->C
        3) echo "$BASE_URL $USER_C $WALLET_C $WALLET_A 100 $KEY" >> "$CMD_FILE" ;;   # C->A
        4) echo "$BASE_URL $USER_A $WALLET_A $WALLET_B 999999999 $KEY" >> "$CMD_FILE" ;; # overdraft
    esac
done

# ── Fire all 50 concurrently ──────────────────────────────────────────────────
echo "Firing $CONCURRENCY concurrent cross-transfers (A->B & B->A deadlock probe)..."
touch "$OUT_DIR/status_codes.txt"

while IFS= read -r line; do
    (
        URL=$(printf '%s' "$line" | awk '{print $1}')
        USER=$(printf '%s' "$line" | awk '{print $2}')
        FROM=$(printf '%s' "$line" | awk '{print $3}')
        TO=$(printf '%s' "$line" | awk '{print $4}')
        AMT=$(printf '%s' "$line" | awk '{print $5}')
        KEY=$(printf '%s' "$line" | awk '{print $6}')
        PAYLOAD="{\"from\":\"$FROM\",\"to\":\"$TO\",\"amount_paise\":$AMT,\"idempotency_key\":\"$KEY\"}"
        FULL=$(curl -s -w '\n%{http_code}' \
            -X POST "$URL/transfers" \
            -H "Authorization: Bearer $USER" \
            -H "Content-Type: application/json" \
            -d "$PAYLOAD" 2>/dev/null || true)
        CODE=$(printf '%s\n' "$FULL" | tail -1)
        printf '%s\n' "${CODE:-000}" >> "$OUT_DIR/status_codes.txt"
    ) &
done < "$CMD_FILE"
wait
echo "All $CONCURRENCY requests complete."

# ── Status Summary ────────────────────────────────────────────────────────────
echo ""
echo "HTTP Status Distribution:"
sort "$OUT_DIR/status_codes.txt" | uniq -c

SERVER_ERRORS=$(grep -c '^500$' "$OUT_DIR/status_codes.txt" 2>/dev/null || echo 0)

# ── Final balances ────────────────────────────────────────────────────────────
BAL_A_FINAL=$(fetch_bal "$WALLET_A" "$USER_A")
BAL_B_FINAL=$(fetch_bal "$WALLET_B" "$USER_B")
BAL_C_FINAL=$(fetch_bal "$WALLET_C" "$USER_C")
SUM_FINAL=$((BAL_A_FINAL + BAL_B_FINAL + BAL_C_FINAL))

echo ""
echo "Final Balances:"
echo "  Wallet A: $BAL_A_FINAL paise"
echo "  Wallet B: $BAL_B_FINAL paise"
echo "  Wallet C: $BAL_C_FINAL paise"
echo "  Total Final:    $SUM_FINAL paise"
echo "  Total Initial:  $SUM_INIT paise"
echo "  HTTP 500 count: $SERVER_ERRORS (Expected: 0)"

# ── Assertions ────────────────────────────────────────────────────────────────
CONSERVED=0
NO_OVERDRAFT=0
NO_DEADLOCK=0

if [ "$SUM_FINAL" -eq "$SUM_INIT" ]; then
    CONSERVED=1
    echo "  [PASS] Money strictly conserved"
else
    DIFF=$((SUM_FINAL - SUM_INIT))
    echo "  [FAIL] Conservation broken! Discrepancy: $DIFF paise"
fi

if [ "$BAL_A_FINAL" -ge 0 ] && [ "$BAL_B_FINAL" -ge 0 ] && [ "$BAL_C_FINAL" -ge 0 ]; then
    NO_OVERDRAFT=1
    echo "  [PASS] No negative balances"
else
    echo "  [FAIL] Negative balance detected!"
fi

if [ "$SERVER_ERRORS" -eq 0 ]; then
    NO_DEADLOCK=1
    echo "  [PASS] Zero HTTP 500 errors — no deadlocks"
else
    echo "  [FAIL] $SERVER_ERRORS HTTP 500 errors (possible deadlock or unhandled exception)"
fi

echo ""
if [ "$CONSERVED" -eq 1 ] && [ "$NO_OVERDRAFT" -eq 1 ] && [ "$NO_DEADLOCK" -eq 1 ]; then
    echo "✅ GATE 3 PASSED: Conservation, no-overdraft, and deadlock-free contention verified!"
    exit 0
else
    echo "❌ GATE 3 FAILED: One or more invariants violated!"
    exit 1
fi
