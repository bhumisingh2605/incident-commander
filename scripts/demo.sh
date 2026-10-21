#!/usr/bin/env bash
# End-to-end demo: break payment-service, let Prometheus/Alertmanager fire, watch Commander
# diagnose it, approve the proposed restart, and see the incident resolve.
#
# Prerequisites: the full stack is running (make up), Ollama is serving qwen3:8b,
#                curl and python3 are installed.
# Usage: scripts/demo.sh [--auto-approve]
set -u

COMMANDER_URL="${COMMANDER_URL:-http://localhost:8080}"
ORDER_URL="${ORDER_URL:-http://localhost:8081}"
PAYMENT_URL="${PAYMENT_URL:-http://localhost:8082}"
POLL="${POLL_SECONDS:-5}"
AUTO=0; [ "${1:-}" = "--auto-approve" ] && AUTO=1

root="$(cd "$(dirname "$0")/.." && pwd)"
if [ -f "$root/.env" ]; then set -a; . "$root/.env"; set +a; fi
for cmd in curl python3; do command -v "$cmd" >/dev/null || { echo "$cmd is required"; exit 1; }; done
[ -n "${COMMANDER_OPERATOR_PASSWORD:-}" ] || { echo "Run 'make secrets' first."; exit 1; }
OP="operator:$COMMANDER_OPERATOR_PASSWORD"

api()  { curl -s -m 30 -u "$OP" "$@"; }
pyj()  { python3 -c "import sys,json
try:
    d=json.load(sys.stdin)
except Exception:
    d=None
$1"; }
step() { printf '\n\033[1m== %s\033[0m\n' "$*"; }
note() { printf '   %s\n' "$*"; }

TRAFFIC_PID=""
start_traffic() {
  ( while true; do
      curl -s -o /dev/null -m 5 -X POST "$ORDER_URL/orders" -H 'Content-Type: application/json' -d '{"item":"book","amount":499}'
      sleep 0.3
    done ) >/dev/null 2>&1 &
  TRAFFIC_PID=$!
}
stop_traffic() { [ -n "$TRAFFIC_PID" ] && kill "$TRAFFIC_PID" 2>/dev/null; TRAFFIC_PID=""; }
cleanup() { stop_traffic; curl -s -m 5 -X POST "$PAYMENT_URL/chaos/reset" >/dev/null 2>&1; }
trap cleanup EXIT

step "Checking that Commander is up"
curl -fs -m 10 "$COMMANDER_URL/actuator/health" >/dev/null || { echo "Commander is not reachable at $COMMANDER_URL. Run 'make up' first."; exit 1; }
note "Commander is healthy."

baseline="$(api "$COMMANDER_URL/api/incidents" | pyj 'print(max([i["id"] for i in d], default=0) if d else 0)')"

step "Injecting a fault: payment-service starts returning errors"
curl -fs -m 10 -X POST "$PAYMENT_URL/chaos/error?enabled=true" >/dev/null || { echo "Could not reach payment-service at $PAYMENT_URL"; exit 1; }
start_traffic
note "Sending order traffic so Prometheus sees the failures (alert fires after ~1-2 minutes)."

step "Waiting for Alertmanager to open an incident"
incident=""
for _ in $(seq 1 $((300 / POLL))); do
  incident="$(api "$COMMANDER_URL/api/incidents" | pyj "print(max([i['id'] for i in d if i['id'] > $baseline], default='') if d else '')")"
  [ -n "$incident" ] && break
  sleep "$POLL"
done
[ -n "$incident" ] || { echo "No incident appeared within 5 minutes. Check Prometheus (http://localhost:9090/alerts) and Alertmanager (http://localhost:9093)."; exit 1; }
note "Incident #$incident opened."

step "Waiting for the AI investigation (local model: this can take a few minutes)"
last=""; status=""
for _ in $(seq 1 $((1200 / POLL))); do
  status="$(api "$COMMANDER_URL/api/incidents/$incident" | pyj 'print(d["status"] if d else "")')"
  [ "$status" != "$last" ] && { note "status: $status"; last="$status"; }
  case "$status" in AWAITING_APPROVAL|ESCALATED|RESOLVED) break ;; esac
  sleep "$POLL"
done

api "$COMMANDER_URL/api/incidents/$incident" | pyj 'print("   diagnosis : %s / %s (confidence %s)\n   summary   : %s" % (d.get("rc_service"), d.get("rc_category"), d.get("rc_confidence"), d.get("rc_summary")))'

if [ "$status" != "AWAITING_APPROVAL" ]; then
  echo; echo "Incident ended in status $status, so there is no proposal to approve (this is the safe fallback when the diagnosis is uncertain or the model is unavailable)."
  exit 2
fi

proposal="$(api "$COMMANDER_URL/api/proposals?status=PENDING" | pyj "print(next((p['id'] for p in d if p['incident_id'] == $incident), '') if d else '')")"
step "Proposed remediation"
api "$COMMANDER_URL/api/proposals?status=PENDING" | pyj "
for p in (d or []):
    if p['incident_id'] == $incident:
        print('   #%s  %s on %s\n   rationale: %s' % (p['id'], p['action_type'], p['target_service'], p['rationale']))"

answer="n"
if [ "$AUTO" -eq 1 ]; then answer="y"
elif [ -t 0 ]; then read -r -p "   Approve and execute proposal #$proposal as operator? [y/N] " answer
fi
if [ "$answer" != "y" ] && [ "$answer" != "Y" ]; then
  echo "   Not approved. To do it yourself:"
  echo "   curl -u operator:\$COMMANDER_OPERATOR_PASSWORD -X POST $COMMANDER_URL/api/proposals/$proposal/approve"
  exit 0
fi

step "Approving and executing"
note "$(api -X POST "$COMMANDER_URL/api/proposals/$proposal/approve")"

step "Waiting for verification and resolution"
last=""
for _ in $(seq 1 $((600 / POLL))); do
  status="$(api "$COMMANDER_URL/api/incidents/$incident" | pyj 'print(d["status"] if d else "")')"
  [ "$status" != "$last" ] && { note "status: $status"; last="$status"; }
  [ "$status" = "RESOLVED" ] && break
  sleep "$POLL"
done

step "Postmortem (first lines)"
api "$COMMANDER_URL/api/incidents/$incident/postmortem" | head -25
echo
echo "Dashboard: $COMMANDER_URL/ui/index.html  (log in as viewer or operator)"
[ "$status" = "RESOLVED" ]