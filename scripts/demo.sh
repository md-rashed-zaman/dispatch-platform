#!/usr/bin/env bash
# The Phase 1 story in one run, against the local stack with real Keycloak logins:
# a bank publishes a job, nearby technicians get offers, two accept at the same moment and exactly one wins,
# the winner works the job and uploads a photo straight to storage, the bank approves.
#
# Needs: the stack and app running (README "Getting started"), the seed loaded, curl, jq, and ImageMagick
# (`magick`) for the sample photo. Press Enter between steps; DEMO_FAST=1 runs straight through.
set -euo pipefail

API=http://localhost:8080
KEYCLOAK=http://localhost:8180/realms/dispatch/protocol/openid-connect/token
WORK_DIR=$(mktemp -d)
trap 'rm -rf "$WORK_DIR"' EXIT

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
api POST "/api/offers/$OFFER/accept" "$RAHIM" > "$WORK_DIR/b.json" &
wait
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

step "Another bank tries to look at this job"
api GET "/api/work-orders/$WORK_ORDER" "$(login dispatcher.shwapno)" | jq '{status, type}'
say "404, not 403: Hibernate's tenant filter and Postgres row-level security both hide it."

printf '\n%sDone.%s The race with 50 technicians at once is proven in FirstAcceptWinsTests (ADR 0005).\n' "$bold" "$reset"
