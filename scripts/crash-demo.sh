#!/usr/bin/env bash
# Live failure demo: the JVM is killed (Runtime.halt, like kill -9) AFTER the employee change is
# committed but BEFORE its event is published. docker compose restarts the app and the outbox relay
# publishes the event on startup: the booking is still cancelled, exactly once.
#
# Requires the app running via docker compose (FAULT_INJECTION_ENABLED=true is set there).
set -euo pipefail

BASE=${BASE:-http://localhost:8080}
HR="acme-hr:acme-hr-pass"
TRANSPORT="acme-transport:acme-transport-pass"
OPS="acme-ops:acme-ops-pass"
ID="C$RANDOM"
TRIP=$(date -d '+3 days' +%F 2>/dev/null || date -v+3d +%F)

PAUSE=${PAUSE:-0}
step() {
  printf '\n\033[1;36m== %s\033[0m\n' "$*"
  if [ "$PAUSE" = "1" ]; then read -r -p "   [press Enter to run] " _; fi
}

step "Create employee $ID and a future booking"
curl -s -u "$HR" -H 'Content-Type: application/json' -X POST "$BASE/api/v1/hr/events" -d "{\"events\":[
 {\"type\":\"CREATE\",\"employeeId\":\"$ID\",\"version\":1,\"name\":\"Crash Test\",\"email\":\"c@acme.com\",\"officeCode\":\"BLR-01\"}]}" >/dev/null
curl -s -u "$TRANSPORT" -H 'Content-Type: application/json' -X POST "$BASE/api/v1/bookings" \
  -d "{\"employeeId\":\"$ID\",\"tripDate\":\"$TRIP\",\"seatNo\":\"1A\"}"; echo
sleep 1

step "Arm the crash: the relay will die right before publishing the next event"
curl -s -u "$OPS" -H 'Content-Type: application/json' -X POST "$BASE/api/v1/ops/faults" -d '{"point":"RELAY_BEFORE_PUBLISH"}'; echo

step "HR deactivates $ID (the DB change + outbox row commit, then the JVM is killed)"
curl -s -u "$HR" -H 'Content-Type: application/json' -X POST "$BASE/api/v1/hr/events" \
  -d "{\"events\":[{\"type\":\"DEACTIVATE\",\"employeeId\":\"$ID\",\"version\":2}]}"; echo

step "Waiting for the app to die and docker to restart it..."
sleep 3
until curl -sf "$BASE/actuator/health" >/dev/null; do printf '.'; sleep 1; done; echo " back up"
sleep 2

step "After restart: the pending event was published and the booking is cancelled"
curl -s -u "$TRANSPORT" "$BASE/api/v1/bookings?employeeId=$ID"; echo
curl -s -u "$TRANSPORT" "$BASE/api/v1/employees/$ID/events"; echo
