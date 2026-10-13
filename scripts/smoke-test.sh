#!/usr/bin/env bash
# End-to-end smoke test against a running stack (docker compose up -d --build).
# Flow: create event -> availability -> purchase (idempotent) -> order transitions -> confirm -> sold.
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
ADMIN_TOKEN="${ADMIN_TOKEN:-local-admin-token}"
RUN_ID="$(date +%s)-$RANDOM"

json() { python3 -c "import json,sys; d=json.load(sys.stdin); print($1)"; }
call() { curl -sS -H 'Content-Type: application/json' "$@"; }
step() { printf '\n== %s\n' "$*"; }

wait_for_status() { # order id, expected status
  for _ in $(seq 1 30); do
    status=$(call "$BASE_URL/api/v1/orders/$1" | json "d['status']")
    [ "$status" = "$2" ] && { echo "order $1 -> $status"; return 0; }
    sleep 1
  done
  echo "order $1 never reached $2 (last: $status)" >&2
  return 1
}

step "1. Create event (capacity 10)"
EVENT=$(call -X POST "$BASE_URL/api/v1/events" \
  -d '{"name":"Rock Night","date":"2035-06-01T20:00:00Z","venue":"Arena","capacity":10}')
echo "$EVENT"
EVENT_ID=$(echo "$EVENT" | json "d['id']")

step "2. Availability before purchase"
call "$BASE_URL/api/v1/events/$EVENT_ID/availability"

step "3. Purchase 3 tickets (202 Accepted, idempotent)"
KEY="smoke-$RUN_ID"
BODY="{\"eventId\":\"$EVENT_ID\",\"customerId\":\"smoke-customer\",\"quantity\":3}"
FIRST=$(call -X POST "$BASE_URL/api/v1/orders" -H "Idempotency-Key: $KEY" -d "$BODY")
echo "$FIRST"
ORDER_ID=$(echo "$FIRST" | json "d['orderId']")
SECOND=$(call -X POST "$BASE_URL/api/v1/orders" -H "Idempotency-Key: $KEY" -d "$BODY")
[ "$(echo "$SECOND" | json "d['orderId']")" = "$ORDER_ID" ] && echo "same key -> same order $ORDER_ID (no duplicate)"

step "4. Order transitions (async consumer)"
wait_for_status "$ORDER_ID" PENDING_CONFIRMATION
call "$BASE_URL/api/v1/orders/$ORDER_ID"

step "5. Availability while held (not a sale yet)"
call "$BASE_URL/api/v1/events/$EVENT_ID/availability"

step "6. Confirm purchase -> tickets SOLD"
call -X POST "$BASE_URL/api/v1/orders/$ORDER_ID/confirm"

step "7. Availability after confirmation"
AVAIL=$(call "$BASE_URL/api/v1/events/$EVENT_ID/availability")
echo "$AVAIL"
[ "$(echo "$AVAIL" | json "d['sold']")" = "3" ] && echo "sold = 3 OK"

step "8. Oversell protection: 20 parallel buyers x 1 ticket on the 7 remaining tickets"
for i in $(seq 1 20); do
  call -X POST "$BASE_URL/api/v1/orders" -H "Idempotency-Key: burst-$RUN_ID-$i" \
    -d "{\"eventId\":\"$EVENT_ID\",\"customerId\":\"burst-$i\",\"quantity\":1}" >/dev/null &
done
wait
sleep 5
AVAIL=$(call "$BASE_URL/api/v1/events/$EVENT_ID/availability")
echo "$AVAIL"
python3 - "$AVAIL" <<'PY'
import json, sys
a = json.loads(sys.argv[1])
held = a["reserved"] + a["pendingConfirmation"]
assert a["available"] >= 0 and a["sold"] + held + a["complimentary"] + a["available"] == a["capacity"], a
assert held == 7, f"exactly the 7 remaining tickets must be held, got {held}"
print("no oversell: 7 held, 0 available OK")
PY

step "9. Complimentary tickets need the admin token"
echo "without token -> HTTP $(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/api/v1/events/$EVENT_ID/complimentary" -H 'Content-Type: application/json' -d '{"quantity":1}')"

step "Done"
