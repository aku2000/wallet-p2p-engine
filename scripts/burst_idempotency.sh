#!/usr/bin/env bash
# ==============================================================================
# Gate 2 Live Probe: Idempotent Exactly-Once Transfer Storm
#
# Fires the same transfer (identical body & idempotency_key) K=30 times concurrently.
# Expects:
#   - All 30 responses share exactly ONE transfer ID
#   - Same key + DIFFERENT body → HTTP 409 Conflict
#
# Usage:
#   ./scripts/burst_idempotency.sh [BASE_URL] [FROM_WALLET] [TO_WALLET] [SENDER_USER_ID]
# ==============================================================================
# NOTE: no set -e here — subshells must handle errors individually
set -uo pipefail

BASE_URL="${1:-http://localhost:8080}"
K=30
IDEMPOTENCY_KEY="storm-key-$(date +%s)-$RANDOM"
AMOUNT_PAISE=1000  # ₹10.00
OUT_DIR=$(mktemp -d)
trap 'rm -rf "$OUT_DIR"' EXIT

echo "================================================================="
echo "⚡ GATE 2 BURST: Idempotent Exactly-Once Transfer Storm"
echo "  Target URL:      $BASE_URL"
echo "  Concurrency:     $K simultaneous requests"
echo "  Idempotency Key: $IDEMPOTENCY_KEY"
echo "================================================================="

# ── Wallet Setup ─────────────────────────────────────────────────────────────
if [ -z "${2:-}" ] || [ -z "${3:-}" ]; then
    echo "Creating test wallets..."
    USER_A="alice-idemp-$(date +%s)"
    USER_B="bob-idemp-$(date +%s)"
    RESP_A=$(curl -sf -X POST "$BASE_URL/wallets" -H "Authorization: Bearer $USER_A" || echo '{}')
    RESP_B=$(curl -sf -X POST "$BASE_URL/wallets" -H "Authorization: Bearer $USER_B" || echo '{}')
    FROM_WALLET=$(printf '%s' "$RESP_A" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
    TO_WALLET=$(printf '%s' "$RESP_B" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
    SENDER_USER="$USER_A"
else
    FROM_WALLET="$2"
    TO_WALLET="$3"
    SENDER_USER="${4:-alice}"
fi

if [ -z "$FROM_WALLET" ] || [ -z "$TO_WALLET" ]; then
    echo "❌ Could not create wallets. Check the server is up."
    exit 1
fi

echo "  Sender Wallet:   $FROM_WALLET (User: $SENDER_USER)"
echo "  Receiver Wallet: $TO_WALLET"

# ── Balance before ─────────────────────────────────────────────────────────────
BAL_RESP=$(curl -sf "$BASE_URL/wallets/$FROM_WALLET" -H "Authorization: Bearer $SENDER_USER" || echo '{"balance":0}')
BAL_BEFORE=$(printf '%s' "$BAL_RESP" | grep -o '"balance":[0-9]*' | cut -d: -f2 || echo 0)
echo "  Sender balance before: ${BAL_BEFORE} paise"
echo ""

# ── Seed balance if needed ────────────────────────────────────────────────────
if [ "${BAL_BEFORE:-0}" -lt "$AMOUNT_PAISE" ]; then
    echo "⚠️  Balance too low — transfers will return 422 (no funds)."
    echo "   Idempotency is still testable: all 30 should return the SAME 422 response with SAME id."
    echo ""
fi

PAYLOAD='{"from":"'"$FROM_WALLET"'","to":"'"$TO_WALLET"'","amount_paise":'"$AMOUNT_PAISE"',"idempotency_key":"'"$IDEMPOTENCY_KEY"'","note":"Idempotency probe"}'

# ── Fire 30 concurrent identical requests ─────────────────────────────────────
echo "Firing $K simultaneous identical POST /transfers..."
for i in $(seq 1 "$K"); do
    (
        # curl -s: silent, -w appends HTTP code on new line
        FULL=$(curl -s -w '\n%{http_code}' \
            -X POST "$BASE_URL/transfers" \
            -H "Authorization: Bearer $SENDER_USER" \
            -H "Content-Type: application/json" \
            -d "$PAYLOAD" 2>/dev/null || true)
        # Last line = HTTP code, everything before = body
        HTTP_CODE=$(printf '%s\n' "$FULL" | tail -1)
        BODY=$(printf '%s\n' "$FULL" | sed '$d')
        printf '%s %s\n' "${HTTP_CODE:-000}" "$BODY" > "$OUT_DIR/resp_$i.txt"
    ) &
done
wait
echo "All $K requests complete."

# ── Analysis ───────────────────────────────────────────────────────────────────
echo ""
echo "--- Storm Results ---"
ALL_CODES=$(awk '{print $1}' "$OUT_DIR"/resp_*.txt 2>/dev/null | sort | uniq -c)
echo "HTTP Status Code distribution:"
echo "$ALL_CODES"

# Extract unique transfer IDs from all responses
TRANSFER_IDS=$(awk '{$1=""; print $0}' "$OUT_DIR"/resp_*.txt 2>/dev/null \
    | grep -o '"id":"[^"]*"' | sort -u || true)
DISTINCT_TX_COUNT=$(printf '%s\n' "$TRANSFER_IDS" | grep -c '"id"' 2>/dev/null || echo 0)

echo ""
echo "Unique Transfer IDs across all 30 responses:"
echo "${TRANSFER_IDS:-<none found>}"
echo "Distinct transfer ID count: $DISTINCT_TX_COUNT (Expected: 1)"

# ── Balance after ────────────────────────────────────────────────────────────
BAL_RESP2=$(curl -sf "$BASE_URL/wallets/$FROM_WALLET" -H "Authorization: Bearer $SENDER_USER" || echo '{"balance":0}')
BAL_AFTER=$(printf '%s' "$BAL_RESP2" | grep -o '"balance":[0-9]*' | cut -d: -f2 || echo 0)
echo "  Sender balance after: ${BAL_AFTER} paise"

# ── 409 Conflict test ────────────────────────────────────────────────────────
echo ""
echo "Testing same key with DIFFERENT body (expect HTTP 409 Conflict)..."
DIFF_PAYLOAD='{"from":"'"$FROM_WALLET"'","to":"'"$TO_WALLET"'","amount_paise":999999,"idempotency_key":"'"$IDEMPOTENCY_KEY"'","note":"Conflict payload"}'
CONFLICT_FULL=$(curl -s -w '\n%{http_code}' \
    -X POST "$BASE_URL/transfers" \
    -H "Authorization: Bearer $SENDER_USER" \
    -H "Content-Type: application/json" \
    -d "$DIFF_PAYLOAD" 2>/dev/null || true)
CONFLICT_CODE=$(printf '%s\n' "$CONFLICT_FULL" | tail -1)
CONFLICT_BODY=$(printf '%s\n' "$CONFLICT_FULL" | sed '$d')

echo "  Conflict test HTTP code: $CONFLICT_CODE (Expected: 409)"
echo "  Conflict test response:  $CONFLICT_BODY"

# ── Verdict ──────────────────────────────────────────────────────────────────
echo ""
if [ "${DISTINCT_TX_COUNT}" -eq 1 ] && [ "${CONFLICT_CODE}" -eq 409 ]; then
    echo "✅ GATE 2 PASSED: Exactly-once transfer invariant verified."
    echo "   All $K concurrent requests returned identical transfer ID."
    echo "   Conflict payload (different body, same key) correctly returned 409."
    exit 0
else
    echo "❌ GATE 2 FAILED"
    [ "${DISTINCT_TX_COUNT}" -ne 1 ] && echo "   → Distinct transfer IDs: $DISTINCT_TX_COUNT (expected 1)"
    [ "${CONFLICT_CODE}" -ne 409 ] && echo "   → Conflict HTTP code: $CONFLICT_CODE (expected 409)"
    exit 1
fi
