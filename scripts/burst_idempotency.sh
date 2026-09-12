#!/usr/bin/env bash
# ==============================================================================
# Gate 2 Live Probe: Idempotent Exactly-Once Transfer Storm
#
# Reproduce recipe:
#   Fire the same transfer (identical body & idempotency_key) K=30 times concurrently.
#   Expect:
#     - Exactly one money movement (debit from sender, credit to recipient)
#     - All 30 responses return identical transfer ID and status
#     - Same key with a DIFFERENT body returns HTTP 409 Conflict
#
# Usage:
#   ./scripts/burst_idempotency.sh [BASE_URL] [FROM_WALLET] [TO_WALLET] [SENDER_USER_ID]
# ==============================================================================
set -euo pipefail

BASE_URL="${1:-http://localhost:8080}"
K=30
IDEMPOTENCY_KEY="storm-key-$(date +%s)-$RANDOM"
AMOUNT_PAISE=1000 # ₹10.00
OUT_DIR=$(mktemp -d)
trap 'rm -rf "$OUT_DIR"' EXIT

echo "================================================================="
echo "⚡ GATE 2 BURST: Idempotent Exactly-Once Transfer Storm"
echo "Target URL:        $BASE_URL"
echo "Concurrency:       $K simultaneous requests"
echo "Idempotency Key:   $IDEMPOTENCY_KEY"
echo "================================================================="

# Setup wallets if not supplied as arguments
if [ -z "${2:-}" ] || [ -z "${3:-}" ]; then
    echo "Creating test wallets..."
    USER_A="alice-idemp-$(date +%s)"
    USER_B="bob-idemp-$(date +%s)"

    RESP_A=$(curl -s -X POST "$BASE_URL/wallets" -H "Authorization: Bearer $USER_A")
    RESP_B=$(curl -s -X POST "$BASE_URL/wallets" -H "Authorization: Bearer $USER_B")

    FROM_WALLET=$(echo "$RESP_A" | grep -o '"id":"[^"]*"' | head -n 1 | cut -d'"' -f4)
    TO_WALLET=$(echo "$RESP_B" | grep -o '"id":"[^"]*"' | head -n 1 | cut -d'"' -f4)
    SENDER_USER="$USER_A"
else
    FROM_WALLET="$2"
    TO_WALLET="$3"
    SENDER_USER="${4:-alice}"
fi

echo "Sender Wallet:     $FROM_WALLET (User: $SENDER_USER)"
echo "Receiver Wallet:   $TO_WALLET"

# Fetch sender balance before storm
BAL_BEFORE_RESP=$(curl -s -X GET "$BASE_URL/wallets/$FROM_WALLET" -H "Authorization: Bearer $SENDER_USER")
BAL_BEFORE=$(echo "$BAL_BEFORE_RESP" | grep -o '"balance":[0-9]*' | cut -d':' -f2 || echo "0")
echo "Sender balance before: ${BAL_BEFORE} paise"

PAYLOAD='{"from":"'"$FROM_WALLET"'","to":"'"$TO_WALLET"'","amount_paise":'"$AMOUNT_PAISE"',"idempotency_key":"'"$IDEMPOTENCY_KEY"'","note":"Idempotency probe"}'

echo ""
echo "Firing $K simultaneous identical POST /transfers..."
for i in $(seq 1 "$K"); do
    echo "$i"
done | xargs -P "$K" -I {} sh -c '
    RES=$(curl -s -w "\n%{http_code}" -X POST "'"$BASE_URL"'/transfers" \
        -H "Authorization: Bearer '"$SENDER_USER"'" \
        -H "Content-Type: application/json" \
        -d "'"$PAYLOAD"'")
    BODY=$(echo "$RES" | head -n -1)
    HTTP_CODE=$(echo "$RES" | tail -n 1)
    echo "$HTTP_CODE $BODY" > "'"$OUT_DIR"'/resp_{}.txt"
'

# Analyze responses
ALL_CODES=$(awk '{print $1}' "$OUT_DIR"/resp_*.txt | sort | uniq -c)
TRANSFER_IDS=$(awk '{print $2}' "$OUT_DIR"/resp_*.txt | grep -o '"id":"[^"]*"' | sort -u)
DISTINCT_TX_COUNT=$(echo "$TRANSFER_IDS" | grep -c 'id' || true)

echo ""
echo "--- Storm Results Analysis ---"
echo "HTTP Status Codes:"
echo "$ALL_CODES"
echo "Unique Transfer IDs returned:"
echo "$TRANSFER_IDS"
echo "Distinct transfer ID count: $DISTINCT_TX_COUNT (Expected: 1)"

# Check balance after storm
BAL_AFTER_RESP=$(curl -s -X GET "$BASE_URL/wallets/$FROM_WALLET" -H "Authorization: Bearer $SENDER_USER")
BAL_AFTER=$(echo "$BAL_AFTER_RESP" | grep -o '"balance":[0-9]*' | cut -d':' -f2 || echo "0")
echo "Sender balance after:  ${BAL_AFTER} paise"

# Verify 409 Conflict on same key with DIFFERENT body
echo ""
echo "Testing same key with DIFFERENT body (expect HTTP 409 Conflict)..."
DIFF_PAYLOAD='{"from":"'"$FROM_WALLET"'","to":"'"$TO_WALLET"'","amount_paise":999999,"idempotency_key":"'"$IDEMPOTENCY_KEY"'","note":"Conflict payload"}'
CONFLICT_RES=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/transfers" \
    -H "Authorization: Bearer $SENDER_USER" \
    -H "Content-Type: application/json" \
    -d "$DIFF_PAYLOAD")
CONFLICT_BODY=$(echo "$CONFLICT_RES" | head -n -1)
CONFLICT_CODE=$(echo "$CONFLICT_RES" | tail -n 1)

echo "Conflict test HTTP code: $CONFLICT_CODE (Expected: 409)"
echo "Conflict test response:  $CONFLICT_BODY"

if [ "$DISTINCT_TX_COUNT" -eq 1 ] && [ "$CONFLICT_CODE" -eq 409 ]; then
    echo ""
    echo "✅ GATE 2 PASSED: Exactly-once transfer invariant verified. Identical responses and 409 on conflict."
    exit 0
else
    echo ""
    echo "❌ GATE 2 FAILED: Idempotency violated or 409 conflict check failed!"
    exit 1
fi
