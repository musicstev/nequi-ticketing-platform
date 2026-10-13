#!/usr/bin/env bash
# Runnable walk-through of the main flows with plain curl (needs bash, curl and python3).
# Usage: docker compose up -d --build && ./postman/curl-examples.sh
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
ADMIN_TOKEN="${ADMIN_TOKEN:-local-admin-token}"
KEY="curl-$(date +%s)-$RANDOM"
field() { python3 -c "import json,sys; print(json.load(sys.stdin)['$1'])"; }
show() { python3 -m json.tool; }

echo "# 1. Create an event"
EVENT_ID=$(curl -sS -X POST "$BASE_URL/api/v1/events" -H 'Content-Type: application/json' \
  -d '{"name":"Rock Night","date":"2035-06-01T20:00:00Z","venue":"Arena","capacity":50}' | tee /dev/stderr | field id)
echo

echo "# 2. List events / get one / availability"
curl -sS "$BASE_URL/api/v1/events" | show
curl -sS "$BASE_URL/api/v1/events/$EVENT_ID" | show
curl -sS "$BASE_URL/api/v1/events/$EVENT_ID/availability" | show

echo "# 3. Availability stream (SSE): first events, then stop"
curl -sS -N --max-time 5 "$BASE_URL/api/v1/events/$EVENT_ID/availability/stream" || true
echo

echo "# 4. Purchase 2 tickets: 202 Accepted with an orderId"
BODY="{\"eventId\":\"$EVENT_ID\",\"customerId\":\"customer-001\",\"quantity\":2}"
ORDER_ID=$(curl -sS -X POST "$BASE_URL/api/v1/orders" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $KEY" -d "$BODY" | tee /dev/stderr | field orderId)
echo

echo "# 5. Replay with the same Idempotency-Key: same orderId, no duplicate"
curl -sS -X POST "$BASE_URL/api/v1/orders" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $KEY" -d "$BODY" | show

echo "# 6. Poll the order until PENDING_CONFIRMATION"
for _ in $(seq 1 30); do
  STATUS=$(curl -sS "$BASE_URL/api/v1/orders/$ORDER_ID" | field status)
  echo "status: $STATUS"
  [ "$STATUS" = "PENDING_CONFIRMATION" ] && break
  sleep 1
done
curl -sS "$BASE_URL/api/v1/orders/$ORDER_ID" | show

echo "# 7. Confirm: tickets become SOLD"
curl -sS -X POST "$BASE_URL/api/v1/orders/$ORDER_ID/confirm" | show
curl -sS "$BASE_URL/api/v1/events/$EVENT_ID/availability" | show

echo "# 8. Complimentary tickets: 201 with the admin token, 403 without"
curl -sS -X POST "$BASE_URL/api/v1/events/$EVENT_ID/complimentary" -H 'Content-Type: application/json' \
  -H "X-Admin-Token: $ADMIN_TOKEN" -d '{"quantity":2}' | show
echo "without token -> HTTP $(curl -s -o /dev/null -w '%{http_code}' -X POST \
  "$BASE_URL/api/v1/events/$EVENT_ID/complimentary" -H 'Content-Type: application/json' -d '{"quantity":1}')"

echo "# 9. Error cases (RFC 9457 problem documents)"
code() { curl -s -o /dev/null -w '%{http_code}' "$@"; }
JSON='Content-Type: application/json'
echo "unknown event       -> HTTP $(code "$BASE_URL/api/v1/events/nope/availability")"
echo "no Idempotency-Key  -> HTTP $(code -X POST "$BASE_URL/api/v1/orders" -H "$JSON" -d "$BODY")"
OTHER="{\"eventId\":\"$EVENT_ID\",\"customerId\":\"customer-001\",\"quantity\":5}"
echo "same key, other qty -> HTTP $(code -X POST "$BASE_URL/api/v1/orders" -H "$JSON" -H "Idempotency-Key: $KEY" -d "$OTHER")"
