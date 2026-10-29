#!/usr/bin/env bash
# Walks through the main flows with curl. Start the app first (docker compose up --build), then:
#   ./scripts/demo.sh            (or PAUSE=1 ./scripts/demo.sh to stop before each step)
# Needs: curl, and jq for pretty output (optional).
set -euo pipefail

BASE=${BASE:-http://localhost:8080}
HR="acme-hr:acme-hr-pass"
TRANSPORT="acme-transport:acme-transport-pass"
OPS="acme-ops:acme-ops-pass"
GLOBEX="globex-transport:globex-transport-pass"
JQ=$(command -v jq || echo cat)
TMP=$(mktemp -d)

# PAUSE=1 waits for Enter before each step (handy while recording a demo)
PAUSE=${PAUSE:-0}
step() {
  printf '\n\033[1;36m== %s\033[0m\n' "$*"
  if [ "$PAUSE" = "1" ]; then read -r -p "   [press Enter to run] " _; fi
}
post() { curl -s -u "$1" -H 'Content-Type: application/json' -X POST "$BASE$2" -d "$3" | $JQ; }
get()  { curl -s -u "$1" "$BASE$2" | $JQ; }

step "1. HR pushes 3 new joiners (one has a malformed phone -> rejected, the batch still succeeds)"
post "$HR" /api/v1/hr/events '{"events":[
 {"type":"CREATE","employeeId":"E1001","version":1,"name":"Priya Sharma","email":"priya@acme.com","phone":"+919800000001","managerId":"M1","officeCode":"BLR-01","shiftCode":"MORNING"},
 {"type":"CREATE","employeeId":"E1002","version":1,"name":"Rahul Verma","email":"rahul@acme.com","phone":"98-BAD","managerId":"M1","officeCode":"BLR-01","shiftCode":"MORNING"},
 {"type":"CREATE","employeeId":"E1003","version":1,"name":"Neha Iyer","email":"neha@acme.com","phone":"+919800000003","managerId":"M1","officeCode":"HYD-01","shiftCode":"NIGHT"}]}'

step "2. Out of order: v7 (office transfer) arrives BEFORE v6 (shift change). v6 is ignored as STALE"
post "$HR" /api/v1/hr/events '{"events":[{"type":"UPDATE","employeeId":"E1001","version":7,"name":"Priya Sharma","email":"priya@acme.com","phone":"+919800000001","managerId":"M1","officeCode":"BLR-02","shiftCode":"EVENING"}]}'
post "$HR" /api/v1/hr/events '{"events":[{"type":"UPDATE","employeeId":"E1001","version":6,"name":"Priya Sharma","email":"priya@acme.com","phone":"+919800000001","managerId":"M1","officeCode":"BLR-01","shiftCode":"NIGHT"}]}'
get "$TRANSPORT" /api/v1/employees/E1001

step "3. Duplicate delivery of v7 is a no-op (DUPLICATE)"
post "$HR" /api/v1/hr/events '{"events":[{"type":"UPDATE","employeeId":"E1001","version":7,"name":"Priya Sharma","email":"priya@acme.com","phone":"+919800000001","managerId":"M1","officeCode":"BLR-02","shiftCode":"EVENING"}]}'

step "4. Field ownership: transport admin sets the pickup point; a later HR update cannot overwrite it"
curl -s -u "$TRANSPORT" -H 'Content-Type: application/json' -X PUT "$BASE/api/v1/employees/E1001/transport" \
  -d '{"pickupPoint":"Gate 3, Prestige Tech Park","specialAssistance":"none"}' | $JQ
post "$HR" /api/v1/hr/events '{"events":[{"type":"UPDATE","employeeId":"E1001","version":8,"name":"Priya Sharma","email":"priya@acme.com","phone":"+919800000001","managerId":"M2","officeCode":"BLR-02","shiftCode":"EVENING","pickupPoint":"HR tries Gate 9"}]}'
get "$TRANSPORT" /api/v1/employees/E1001

step "5. Tenant isolation: another client's admin gets 404 for our employee"
curl -s -o /dev/null -w 'globex reading acme/E1001 -> HTTP %{http_code}\n' -u "$GLOBEX" "$BASE/api/v1/employees/E1001"

step "6. Deactivation cancels future bookings (via the outbox)"
post "$TRANSPORT" /api/v1/bookings "{\"employeeId\":\"E1003\",\"tripDate\":\"$(date -d '+2 days' +%F 2>/dev/null || date -v+2d +%F)\",\"seatNo\":\"4C\"}"
post "$HR" /api/v1/hr/events '{"events":[{"type":"DEACTIVATE","employeeId":"E1003","version":2}]}'
sleep 1
get "$TRANSPORT" "/api/v1/bookings?employeeId=E1003"

step "7. Nightly export of 1,001 employees (1,000 joiners + E1001)"
{
  echo "employeeId,version,name,email,phone,managerId,officeCode,shiftCode"
  # the export also contains the employee created through the API earlier (same data, version 8)
  echo "E1001,8,Priya Sharma,priya@acme.com,+919800000001,M2,BLR-02,EVENING"
  for i in $(seq 2000 2999); do echo "E$i,1,Person $i,p$i@acme.com,+91980000$i,M1,BLR-01,MORNING"; done
} > "$TMP/night1.csv"
curl -s -u "$HR" -F "file=@$TMP/night1.csv" "$BASE/api/v1/hr/snapshots" | $JQ

step "8. Broken export: the file is cut off at 600 of 1,000 rows -> SAFETY BRAKE, nobody deactivated"
head -n 601 "$TMP/night1.csv" > "$TMP/cut.csv"
RUN=$(curl -s -u "$HR" -F "file=@$TMP/cut.csv" "$BASE/api/v1/hr/snapshots")
echo "$RUN" | $JQ
RUN_ID=$(echo "$RUN" | sed -E 's/.*"id":"([^"]+)".*/\1/')
get "$OPS" "/api/v1/reconciliations/$RUN_ID/missing?limit=5"

step "9. Operator rejects the broken run"
post "$OPS" "/api/v1/reconciliations/$RUN_ID/reject" ''

step "10. On-call view: outbox lag and counters"
get "$OPS" /api/v1/ops/outbox
curl -s -u "$OPS" "$BASE/actuator/prometheus" | grep -E '^rostersync_(changes_total|outbox|reconciliation_brake)' | head -20

rm -rf "$TMP"
printf '\n\033[1;32mDemo finished.\033[0m\n'
