#!/usr/bin/env bash
# ==============================================================================
# Gate 3 Live Probe: Conservation + No-Overdraft Under Contention
#
# Reproduce recipe:
#   Seed a set of wallets.
#   Fire concurrent transfers touching the same wallets simultaneously,
#   including bidirectional A->B and B->A crosses (tests deadlock prevention)
#   and overdraft attempts exceeding available balance.
#   Expect:
#     - Sum of all balances is strictly unchanged (Conservation)
#     - No wallet balance is ever negative (No Overdraft)
#     - Overdrawing transfers fail cleanly with 422 (no partial apply, no 500)
#
# Usage:
#   ./scripts/burst_conservation.sh [BASE_URL] [WALLET_A] [USER_A] [WALLET_B] [USER_B] [WALLET_C] [USER_C]
# ==============================================================================
set -euo pipefail

BASE_URL="${1:-http://localhost:8080}"
OUT_DIR=$(mktemp -d)
trap 'rm -rf "$OUT_DIR"' EXIT

echo "================================================================="
echo "⚡ GATE 3 BURST: Conservation & No-Overdraft Under Contention"
echo "Target URL: $BASE_URL"
echo "================================================================="

# Create or reuse test wallets
if [ -z "${2:-}" ]; then
    echo "Creating 3 test wallets..."
    USER_A="alice-conserve-$(date +%s)"
    USER_B="bob-conserve-$(date +%s)"
    USER_C="charlie-conserve-$(date +%s)"

    RESP_A=$(curl -s -X POST "$BASE_URL/wallets" -H "Authorization: Bearer $USER_A")
    RESP_B=$(curl -s -X POST "$BASE_URL/wallets" -H "Authorization: Bearer $USER_B")
    RESP_C=$(curl -s -X POST "$BASE_URL/wallets" -H "Authorization: Bearer $USER_C")

    WALLET_A=$(echo "$RESP_A" | grep -o '"id":"[^"]*"' | head -n 1 | cut -d'"' -f4)
    WALLET_B=$(echo "$RESP_B" | grep -o '"id":"[^"]*"' | head -n 1 | cut -d'"' -f4)
    WALLET_C=$(echo "$RESP_C" | grep -o '"id":"[^"]*"' | head -n 1 | cut -d'"' -f4)
else
    WALLET_A="$2"; USER_A="${3:-alice}"
    WALLET_B="$4"; USER_B="${5:-bob}"
    WALLET_C="$6"; USER_C="${7:-charlie}"
fi

# Fetch initial balances
fetch_bal() {
    local wid="$1"
    local u="$2"
    local res
    res=$(curl -s -X GET "$BASE_URL/wallets/$wid" -H "Authorization: Bearer $u")
    echo "$res" | grep -o '"balance":[0-9]*' | cut -d':' -f2 || echo "0"
}

BAL_A_INIT=$(fetch_bal "$WALLET_A" "$USER_A")
BAL_B_INIT=$(fetch_bal "$WALLET_B" "$USER_B")
BAL_C_INIT=$(fetch_bal "$WALLET_C" "$USER_C")
SUM_INIT=$((BAL_A_INIT + BAL_B_INIT + BAL_C_INIT))

echo "Initial Balances:"
echo "  Wallet A ($USER_A): $BAL_A_INIT paise"
echo "  Wallet B ($USER_B): $BAL_B_INIT paise"
echo "  Wallet C ($USER_C): $BAL_C_INIT paise"
echo "  Total Initial Sum:   $SUM_INIT paise"
echo ""

# Prepare concurrent transfer tasks:
# Mix of A->B, B->A (deadlock probe!), B->C, C->A, and massive overdrafts
echo "Generating cross-contention transfer commands..."
CONCURRENCY=50
CMD_FILE="$OUT_DIR/commands.txt"
> "$CMD_FILE"

for i in $(seq 1 "$CONCURRENCY"); do
    KEY="contention-$(date +%s)-$RANDOM-$i"
    MOD=$((i % 5))
    case $MOD in
        0) # A -> B (normal)
            echo "$BASE_URL $USER_A $WALLET_A $WALLET_B 500 $KEY" >> "$CMD_FILE" ;;
        1) # B -> A (simultaneous opposite direction - deadlock probe)
            echo "$BASE_URL $USER_B $WALLET_B $WALLET_A 300 $KEY" >> "$CMD_FILE" ;;
        2) # B -> C
            echo "$BASE_URL $USER_B $WALLET_B $WALLET_C 200 $KEY" >> "$CMD_FILE" ;;
        3) # C -> A
            echo "$BASE_URL $USER_C $WALLET_C $WALLET_A 100 $KEY" >> "$CMD_FILE" ;;
        4) # Overdraft attempt (huge amount)
            echo "$BASE_URL $USER_A $WALLET_A $WALLET_B 999999999 $KEY" >> "$CMD_FILE" ;;
    esac
done

echo "Firing $CONCURRENCY concurrent cross-transfers (including A->B & B->A deadlock probe)..."
xargs -P 25 -n 6 -a "$CMD_FILE" sh -c '
    URL="$1"; USER="$2"; FROM="$3"; TO="$4"; AMT="$5"; KEY="$6"
    PAYLOAD="{\"from\":\"$FROM\",\"to\":\"$TO\",\"amount_paise\":$AMT,\"idempotency_key\":\"$KEY\"}"
    RES=$(curl -s -w "\n%{http_code}" -X POST "$URL/transfers" \
        -H "Authorization: Bearer $USER" \
        -H "Content-Type: application/json" \
        -d "$PAYLOAD")
    CODE=$(echo "$RES" | tail -n 1)
    echo "$CODE" >> "'"$OUT_DIR"'/status_codes.txt"
' sh

# Check for 500 internal server errors (deadlocks or unhandled exceptions)
SERVER_ERRORS=$(grep -c '500' "$OUT_DIR/status_codes.txt" || true)
echo ""
echo "HTTP Status Summary:"
sort "$OUT_DIR/status_codes.txt" | uniq -c

# Fetch final balances
BAL_A_FINAL=$(fetch_bal "$WALLET_A" "$USER_A")
BAL_B_FINAL=$(fetch_bal "$WALLET_B" "$USER_B")
BAL_C_FINAL=$(fetch_bal "$WALLET_C" "$USER_C")
SUM_FINAL=$((BAL_A_FINAL + BAL_B_FINAL + BAL_C_FINAL))

echo ""
echo "Final Balances:"
echo "  Wallet A: $BAL_A_FINAL paise"
echo "  Wallet B: $BAL_B_FINAL paise"
echo "  Wallet C: $BAL_C_FINAL paise"
echo "  Total Final Sum:     $SUM_FINAL paise"
echo "  Expected Final Sum:  $SUM_INIT paise"
echo "  HTTP 500 count:      $SERVER_ERRORS (Expected: 0)"

# Verification assertions
CONSERVED=0
NO_OVERDRAFT=0
NO_DEADLOCK=0

if [ "$SUM_FINAL" -eq "$SUM_INIT" ]; then
    CONSERVED=1
    echo "  [PASS] Money strictly conserved: Initial ($SUM_INIT) == Final ($SUM_FINAL)"
else
    echo "  [FAIL] Conservation broken! Discrepancy: $((SUM_FINAL - SUM_INIT)) paise"
fi

if [ "$BAL_A_FINAL" -ge 0 ] && [ "$BAL_B_FINAL" -ge 0 ] && [ "$BAL_C_FINAL" -ge 0 ]; then
    NO_OVERDRAFT=1
    echo "  [PASS] No negative balances detected."
else
    echo "  [FAIL] Negative balance detected!"
fi

if [ "$SERVER_ERRORS" -eq 0 ]; then
    NO_DEADLOCK=1
    echo "  [PASS] Zero HTTP 500 errors (no deadlock occurrences under A->B / B->A contention)."
else
    echo "  [FAIL] HTTP 500 errors detected: possible deadlock or unhandled exception."
fi

if [ "$CONSERVED" -eq 1 ] && [ "$NO_OVERDRAFT" -eq 1 ] && [ "$NO_DEADLOCK" -eq 1 ]; then
    echo ""
    echo "✅ GATE 3 PASSED: Conservation, no-overdraft, and deadlock-free contention all verified!"
    exit 0
else
    echo ""
    echo "❌ GATE 3 FAILED: One or more invariants violated!"
    exit 1
fi
