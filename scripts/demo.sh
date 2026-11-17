#!/usr/bin/env bash
# The Dispatch story in one run, against the local stack with real Keycloak logins:
# a bank publishes a job, nearby technicians get offers, two accept at the same moment and exactly one wins,
# the winner works the job and uploads a photo straight to storage, the bank approves (Phase 1). Meanwhile the
# bank's live dashboard follows every change, emails go out, and one trace follows the publish through the
# outbox and Kafka (Phase 2).
#
# Needs: the stack and app running (README "Getting started"), the seed loaded, curl, jq, and ImageMagick
# (`magick`) for the sample photo. Grafana (lgtm) for the last step; it's skipped without it. Press Enter
# between steps; DEMO_FAST=1 runs straight through.
set -euo pipefail

API=http://localhost:8080
KEYCLOAK=http://localhost:8180/realms/dispatch/protocol/openid-connect/token
GRAFANA=http://localhost:3000
WORK_DIR=$(mktemp -d)
trap 'kill "${LIVE_PID:-}" 2>/dev/null; rm -rf "$WORK_DIR"' EXIT

bold=$'\e[1m'; dim=$'\e[2m'; green=$'\e[32m'; reset=$'\e[0m'

step() {
  printf '\n%s== %s%s\n' "$bold" "$1" "$reset"
  [[ "${DEMO_FAST:-0}" == 1 ]] || read -r -p "${dim}(Enter)${reset}" _
}
say() { printf '%s%s%s\n' "$green" "$1" "$reset"; }

login() {
  curl -sf "$KEYCLOAK" -d grant_type=password -d client_id=dispatch-dev-cli -d username="$1" -d password=password \
    | jq -r .access_token
}
api() { # api METHOD PATH TOKEN [JSON]
  local args=(-s -X "$1" "$API$2" -H "Authorization: Bearer $3")
  [[ $# -ge 4 ]] && args+=(-H 'Content-Type: application/json' -d "$4")
  curl "${args[@]}"
}

step "Log in: a dispatcher at Meghna Bank, and technician Rahim (Gulshan)"
DISPATCHER=$(login dispatcher.meghna)
RAHIM=$(login tech.rahim)
say "Two real Keycloak tokens. The bank's carries tenant_id, Rahim's carries technician_id."
# The bank's dashboard: a live stream of its work order changes for the whole demo (server-sent events).
curl -sN -H "Authorization: Bearer $DISPATCHER" "$API/api/live/work-orders" > "$WORK_DIR/live.txt" &
LIVE_PID=$!

step "The bank creates a job at its Gulshan branch and publishes it"
WORK_ORDER=$(api POST /api/work-orders "$DISPATCHER" '{
  "title": "CCTV camera offline", "description": "Camera 3 in the vault shows no signal.",
  "siteAddress": "Gulshan Avenue, Dhaka", "site": {"latitude": 23.7930, "longitude": 90.4130},
  "requiredSkills": ["cctv"],
  "timeWindow": {"startsAt": "2026-12-01T04:00:00Z", "endsAt": "2026-12-01T08:00:00Z"},
  "budget": {"minor": 300000, "currency": "BDT"}, "checklist": ["Photo of the working camera"]}' | jq -r .id)
api POST "/api/work-orders/$WORK_ORDER/publish" "$DISPATCHER" | jq '{status}'
for _ in $(seq 50); do # matching reacts to the publish event just after the commit
  [[ $(api GET "/api/work-orders/$WORK_ORDER" "$DISPATCHER" | jq -r .status) == ROUTED ]] && break
  sleep 0.1
done
api GET "/api/work-orders/$WORK_ORDER" "$DISPATCHER" | jq '{status}'
say "Publishing returned at once. Matching reacted to the event after the commit: it searched 50,000 technicians for the 10 nearest with the skill (under 1 ms in Postgres) and offered them the job."

step "Rahim sees the offer"
OFFERS=$(api GET /api/me/offers "$RAHIM")
echo "$OFFERS" | jq --arg wo "$WORK_ORDER" '.items[] | select(.workOrderId == $wo) | {title, siteAddress, budget, expiresAt}'
OFFER=$(echo "$OFFERS" | jq -r --arg wo "$WORK_ORDER" '.items[] | select(.workOrderId == $wo) | .id')

step "Rahim taps Accept twice at the same instant (a double tap on a slow network)"
api POST "/api/offers/$OFFER/accept" "$RAHIM" > "$WORK_DIR/a.json" &
first=$!
api POST "/api/offers/$OFFER/accept" "$RAHIM" > "$WORK_DIR/b.json" &
second=$!
wait "$first" "$second" # not a bare wait: the live stream runs in the background for the whole demo
jq -c '{status}' "$WORK_DIR/a.json" "$WORK_DIR/b.json"
say "Both answers say ACCEPTED: accepting again an offer you already won is safe."
api GET "/api/work-orders/$WORK_ORDER" "$DISPATCHER" | jq '{status, technicianId}'
say "The 9 other technicians' offers closed in the same transaction. If any of them accepts now: 409 already-taken."

step "Rahim starts the job and uploads a photo straight to object storage"
api POST "/api/work-orders/$WORK_ORDER/start" "$RAHIM" | jq '{status}'
magick -size 1200x900 plasma:fractal -quality 85 "$WORK_DIR/camera.jpg"
SIZE=$(stat -c %s "$WORK_DIR/camera.jpg" 2>/dev/null || stat -f %z "$WORK_DIR/camera.jpg")
TICKET=$(api POST "/api/work-orders/$WORK_ORDER/attachments" "$RAHIM" \
  "{\"fileName\": \"camera3.jpg\", \"contentType\": \"image/jpeg\", \"size\": $SIZE}")
echo "$TICKET" | jq '{method, headers, url: (.url | split("?")[0])}'
curl -s -o /dev/null -w "PUT to storage: %{http_code}\n" -X PUT -H "Content-Type: image/jpeg" \
  --upload-file "$WORK_DIR/camera.jpg" "$(echo "$TICKET" | jq -r .url)"
api POST "/api/work-orders/$WORK_ORDER/attachments/$(echo "$TICKET" | jq -r .attachmentId)/confirm" "$RAHIM" \
  | jq '{fileName, size}'
say "The API never touched the bytes. It signed a 5-minute URL for this key, type and size, then checked what arrived."

step "Rahim submits; the bank looks at the photo and approves"
api POST "/api/work-orders/$WORK_ORDER/submit" "$RAHIM" | jq '{status}'
api GET "/api/work-orders/$WORK_ORDER/attachments" "$DISPATCHER" | jq '.[] | {fileName, downloadUrlExpiresAt}'
api POST "/api/work-orders/$WORK_ORDER/approve" "$DISPATCHER" | jq '{status}'

step "The bank's dashboard followed it live"
sleep 1 # the last change reaches Kafka and the stream within a second
grep '^data:' "$WORK_DIR/live.txt" | sed 's/^data://' \
  | jq -r --arg wo "$WORK_ORDER" 'select(.workOrderId == $wo) | "\(.from) -> \(.to)"' || true
say "Every change went to Kafka through the outbox, and each app instance pushes it to its own open dashboards (SSE, ADR 0007)."

step "And the bank got its emails"
sleep 3 # queued by the Kafka consumer, then sent by the email sender every 2 s
curl -s "http://localhost:8025/api/v1/search?query=$(jq -rn --arg wo "$WORK_ORDER" '$wo|@uri')" \
  | jq -r '.messages[]? | "\(.To[0].Address): \(.Subject)"' || true
say "Queued in the consumer's transaction, deduplicated by event id, sent through a circuit breaker. Mailpit: http://localhost:8025"

step "One trace from the publish to both consumers"
explore() { # explore TRACEQL: a Grafana Explore link running that query in Tempo
  local panes
  panes=$(jq -rn --arg q "$1" \
    '{a: {datasource: "tempo", queries: [{refId: "A", datasource: {type: "tempo", uid: "tempo"}, queryType: "traceql", query: $q}]}} | tojson | @uri')
  echo "$GRAFANA/explore?schemaVersion=1&panes=$panes"
}
query="{ span.http.url = \"/api/work-orders/$WORK_ORDER/publish\" }"
if ! curl -sf -o /dev/null "$GRAFANA/api/health"; then
  say "(Grafana isn't running: start the lgtm container to see traces and the dashboard.)"
else
  trace=""
  for _ in $(seq 45); do # Tempo makes a trace searchable some seconds after its last span ends
    trace=$(curl -s -G "$GRAFANA/api/datasources/proxy/uid/tempo/api/search" --data-urlencode "q=$query" \
      | jq -r '.traces[0].traceID // empty' 2>/dev/null) || true
    [[ -n "$trace" ]] && break
    sleep 1
  done
  if [[ -n "$trace" ]]; then
    curl -s "$GRAFANA/api/datasources/proxy/uid/tempo/api/traces/$trace" \
      | jq -r '[.batches[] | (.scopeSpans // [])[] | .spans[] | .name]
               | map(select(test("^http post|outbox relay|send$|process$"))) | group_by(.) | map("\(length) x \(.[0])") | .[]'
    say "The request's trace was stored with the outbox event and continued by the relay, so Kafka and both consumers join it."
    explore "$trace"
  else
    say "Tempo hasn't indexed this trace yet; this link finds it in a few seconds:"
    explore "$query"
  fi
  say "Dashboard (SLOs, latency, business metrics): $GRAFANA/d/dispatch-overview"
fi

step "Another bank tries to look at this job"
api GET "/api/work-orders/$WORK_ORDER" "$(login dispatcher.shwapno)" | jq '{status, type}'
say "404, not 403: Hibernate's tenant filter and Postgres row-level security both hide it."

printf '\n%sDone.%s The race with 50 technicians at once is proven in FirstAcceptWinsTests (ADR 0005); what\n' "$bold" "$reset"
printf 'happens when Kafka, Redis or the database misbehave is in docs/failure-modes.md and the chaos-day postmortem.\n'
