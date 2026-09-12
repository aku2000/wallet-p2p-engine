#!/usr/bin/env bash
# ==============================================================================
# Gate 1 Live Probe: Race-Free Wallet Get-or-Create
#
# Reproduce recipe:
#   Fire 50 concurrent POST /wallets requests for a brand-new user.
#   Expect: exactly one wallet created; all 50 responses return the identical wallet_id.
#
# Usage:
#   ./scripts/burst_get_or_create.sh [BASE_URL]
#   Example: ./scripts/burst_get_or_create.sh http://localhost:8080
# ==============================================================================
set -euo pipefail

BASE_URL="${1:-http://localhost:8080}"
CONCURRENCY=50
USER_ID="probe-user-$(date +%s)-$RANDOM"
OUT_DIR=$(mktemp -d)
trap 'rm -rf "$OUT_DIR"' EXIT

echo "================================================================="
echo "⚡ GATE 1 BURST: Race-Free Wallet Get-or-Create"
echo "Target URL:   $BASE_URL"
echo "Concurrency:  $CONCURRENCY simultaneous requests"
echo "Test User:    $USER_ID"
echo "================================================================="

# Generate payload list for xargs
for i in $(seq 1 "$CONCURRENCY"); do
    echo "$i"
done | xargs -P "$CONCURRENCY" -I {} sh -c '
    RES=$(curl -s -w "\n%{http_code}" -X POST "'"$BASE_URL"'/wallets" \
        -H "Authorization: Bearer '"$USER_ID"'" \
        -H "Content-Type: application/json")
    BODY=$(echo "$RES" | head -n -1)
    HTTP_CODE=$(echo "$RES" | tail -n 1)
    echo "$HTTP_CODE $BODY" > "'"$OUT_DIR"'/resp_{}.txt"
'

# Analyze results
ALL_CODES=$(awk '{print $1}' "$OUT_DIR"/resp_*.txt | sort | uniq -c)
WALLET_IDS=$(awk '{print $2}' "$OUT_DIR"/resp_*.txt | grep -o '"id":"[^"]*"' | sort -u)
DISTINCT_WALLET_COUNT=$(echo "$WALLET_IDS" | grep -c 'id' || true)

echo ""
echo "--- Results Analysis ---"
echo "HTTP Status Codes:"
echo "$ALL_CODES"
echo ""
echo "Unique Wallet IDs returned:"
echo "$WALLET_IDS"
echo "Distinct count: $DISTINCT_WALLET_COUNT (Expected: 1)"

if [ "$DISTINCT_WALLET_COUNT" -eq 1 ]; then
    echo ""
    echo "✅ GATE 1 PASSED: Exactly 1 wallet created across $CONCURRENCY concurrent calls."
    exit 0
else
    echo ""
    echo "❌ GATE 1 FAILED: Found $DISTINCT_WALLET_COUNT distinct wallets for the same user!"
    exit 1
fi
