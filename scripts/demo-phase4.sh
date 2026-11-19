#!/usr/bin/env bash
# Phase 4 in one run: money (a payment that can't be paid twice or faked, the ledger, the payout, a refund), the
# AI auto-approval rollout behind a flag, and the MCP tools an AI assistant uses. Claude itself: demo-claude.sh.
#
# Needs: the stack and the app running with payments required (PAYMENTS_REQUIRED=true; the fake SSLCommerz is
# the default), the seed loaded, curl and jq. Press Enter between steps; DEMO_FAST=1 runs straight through.
set -euo pipefail

API=http://localhost:8080
KEYCLOAK=http://localhost:8180/realms/dispatch/protocol/openid-connect/token
MEGHNA=01930000-0000-7000-8000-000000000001

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
uuid() { uuidgen 2>/dev/null || cat /proc/sys/kernel/random/uuid; }

# A job at Meghna Bank, published and accepted by Rahim: where Phase 1's demo ends.
assigned_job() {
  local title=$1 job offer
  job=$(api POST /api/work-orders "$DISPATCHER" "{
    \"title\": \"$title\", \"description\": \"Camera 3 in the vault shows no signal; the recorder lost its network link.\",
    \"siteAddress\": \"Gulshan Avenue, Dhaka\", \"site\": {\"latitude\": 23.7930, \"longitude\": 90.4130},
    \"requiredSkills\": [\"cctv\", \"networking\"],
    \"timeWindow\": {\"startsAt\": \"2026-12-01T04:00:00Z\", \"endsAt\": \"2026-12-01T08:00:00Z\"},
    \"budget\": {\"minor\": 300000, \"currency\": \"BDT\"}, \"checklist\": [\"Photo of the working camera\"]}" | jq -r .id)
  api POST "/api/work-orders/$job/publish" "$DISPATCHER" > /dev/null
  for _ in $(seq 50); do
    offer=$(api GET /api/me/offers "$RAHIM" | jq -r --arg wo "$job" '.items[] | select(.workOrderId == $wo) | .id')
    [[ -n $offer ]] && break
    sleep 0.2
  done
  api POST "/api/offers/$offer/accept" "$RAHIM" > /dev/null
  echo "$job"
}
pay() { # pay JOB KEY: start (or resume) funding with an Idempotency-Key, print the answer's headers and body
  curl -s -i -X POST "$API/api/work-orders/$1/payments" -H "Authorization: Bearer $DISPATCHER" -H "Idempotency-Key: $2"
}
payment() { api GET "/api/work-orders/$1/payment" "$DISPATCHER"; }

step "Log in: a dispatcher at Meghna Bank, technician Rahim, and a platform admin"
DISPATCHER=$(login dispatcher.meghna)
RAHIM=$(login tech.rahim)
ADMIN=$(login ops.admin)
JOB=$(assigned_job "Vault camera offline")
say "A job at Meghna Bank's Gulshan branch, accepted by Rahim. Phase 4 starts here: the money."

step "Rahim can't start before the bank has paid"
api POST "/api/work-orders/$JOB/start" "$RAHIM" | jq '{status, type, detail}'

step "The bank clicks Pay twice (same Idempotency-Key): one payment, the same page"
KEY=$(uuid)
pay "$JOB" "$KEY" | sed -n '/^{/p' | jq '{status, amount, redirectUrl}'
pay "$JOB" "$KEY" | grep -i '^idempotent-replayed' || true
say "The second answer is the first one replayed (Idempotent-Replayed: true). Five at once make one payment too (PaymentFlowTests)."

step "Someone posts a fake 'paid' notice to the public notification URL"
curl -s -o /dev/null -w "IPN answered %{http_code}\n" -X POST "$API/api/payments/sslcommerz/ipn" \
  -d "tran_id=not-a-real-one&val_id=made-up&status=VALID"
payment "$JOB" | jq '{status}'
say "Still PENDING: a notice only names a payment; Dispatch asks SSLCommerz itself, and checks tran_id, amount and currency."

step "The bank pays on the payment page"
PAGE=$(payment "$JOB" | jq -r .redirectUrl)
if [[ $PAGE == */fake-sslcommerz/* ]]; then
  curl -s -o /dev/null -X POST "$PAGE/pay" # the local stand-in's Pay button: no money moves
else
  say "Open $PAGE and pay with an SSLCommerz sandbox card, then press Enter."
  read -r _
fi
for _ in $(seq 50); do [[ $(payment "$JOB" | jq -r .status) == CAPTURED ]] && break; sleep 0.2; done
payment "$JOB" | jq '{status, amount, settledAt}'
api POST "/api/work-orders/$JOB/start" "$RAHIM" | jq '{status}'

step "Rahim finishes, the bank approves: where did the money go?"
api POST "/api/work-orders/$JOB/submit" "$RAHIM" '{"notes": "Camera replaced, feed checked"}' > /dev/null
api POST "/api/work-orders/$JOB/approve" "$DISPATCHER" | jq '{status}'
PAYOUT=""
for _ in $(seq 50); do
  PAYOUT=$(api GET /api/payouts "$ADMIN" | jq -r --arg wo "$JOB" '.[] | select(.workOrderId == $wo) | .id')
  [[ -n $PAYOUT ]] && break
  sleep 0.2
done
api GET "/api/work-orders/$JOB/ledger" "$DISPATCHER" \
  | jq -c '.[] | {kind, lines: [.lines[] | "\(.account) \(if .debit > 0 then "debit \(.debit)" else "credit \(.credit)" end)"]}'
say "Each entry balances, checked by the database at commit: escrow at capture, the gateway's 2.5%, then 10% fee and the rest owed to Rahim."

step "The platform pays Rahim and records the bank's reference"
api POST "/api/payouts/$PAYOUT/confirm" "$ADMIN" '{"bankReference": "DBBL-TRX-90412"}' | jq '{status, amount, bankReference}'
api GET "/api/work-orders/$JOB" "$DISPATCHER" | jq '{status}'
api GET /api/ledger/totals "$ADMIN" | jq '{debits, credits, balanced}'

step "Another funded job is cancelled before work starts: the bank gets its money back"
JOB2=$(assigned_job "POS terminal replacement")
URL=$(pay "$JOB2" "$(uuid)" | sed -n '/^{/p' | jq -r .redirectUrl)
curl -s -o /dev/null -X POST "$URL/pay"
for _ in $(seq 50); do [[ $(payment "$JOB2" | jq -r .status) == CAPTURED ]] && break; sleep 0.2; done
api POST "/api/work-orders/$JOB2/cancel" "$DISPATCHER" | jq '{status}'
for _ in $(seq 25); do [[ $(payment "$JOB2" | jq -r .refund) != null ]] && break; sleep 0.2; done
payment "$JOB2" | jq '{status, refund}'
say "The refund is a second movement of money: posted to the ledger only when SSLCommerz says it's done (the reconciler checks every minute)."

step "AI auto-approval is a flag: on for 20% of companies, picked by a stable hash"
api GET /api/flags/ai.auto-approve "$ADMIN" | jq '{key, enabled, rolloutPercent, version}'
api GET "/api/flags/ai.auto-approve/explain?tenantId=$MEGHNA" "$ADMIN" | jq '{variant, reason, bucket}'
BUCKET=$(api GET "/api/flags/ai.auto-approve/explain?tenantId=$MEGHNA" "$ADMIN" | jq -r '.bucket // "none (on a list)"')
say "Meghna Bank's bucket: $BUCKET, the same for this flag on every request and every instance: it never switches groups."

step "The platform raises the rollout to 50%, with a reason (and puts it back after the demo)"
ETAG=$(curl -s -D - -o /dev/null "$API/api/flags/ai.auto-approve" -H "Authorization: Bearer $ADMIN" | grep -i '^etag' | tr -d '\r' | cut -d' ' -f2)
BEFORE=$(api GET /api/flags/ai.auto-approve "$ADMIN")
curl -s -X PUT "$API/api/flags/ai.auto-approve" -H "Authorization: Bearer $ADMIN" -H "If-Match: $ETAG" \
  -H 'Content-Type: application/json' \
  -d "$(jq -c '{enabled, rolloutPercent: 50, allowTenants, denyTenants, reason: "Demo: guardrail flat for two weeks, widen to 50%"}' <<< "$BEFORE")" \
  | jq '{rolloutPercent, version}'
api GET "/api/flags/ai.auto-approve/explain?tenantId=$MEGHNA" "$ADMIN" | jq '{variant, reason, bucket}'
api GET /api/flags/ai.auto-approve/changes "$ADMIN" | jq -c '.[0:2][] | {reason, changedAt}'
api GET /api/experiments/auto-approve "$ADMIN" | jq -c '.arms[] | {variant, decided, approved, minutesToApprovalP50, aiApprovableRejectedByPerson}'
say "Every change keeps who, why, before and after. The Grafana 'Experiment' row compares the arms (http://localhost:3000)."
ETAG=$(curl -s -D - -o /dev/null "$API/api/flags/ai.auto-approve" -H "Authorization: Bearer $ADMIN" | grep -i '^etag' | tr -d '\r' | cut -d' ' -f2)
curl -s -o /dev/null -X PUT "$API/api/flags/ai.auto-approve" -H "Authorization: Bearer $ADMIN" -H "If-Match: $ETAG" \
  -H 'Content-Type: application/json' -d "$(jq -c '{enabled, rolloutPercent, allowTenants, denyTenants, reason: "Demo over: back to what it was"}' <<< "$BEFORE")"

step "The MCP tools an AI assistant gets: the dispatcher's own, nothing more"
curl -s -X POST "$API/api/mcp" -H "Authorization: Bearer $DISPATCHER" -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}' \
  | sed -n 's/^data://p; /^{/p' | jq -c '.result.tools[] | {name, readOnly: .annotations.readOnlyHint}'
say "Each call carries the user's token: their role and their company decide, as in the app. Next: scripts/demo-claude.sh."

printf '\n%sDone.%s\n' "$bold" "$reset"
