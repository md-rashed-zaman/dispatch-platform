# The ops assistant (MCP)

Dispatch is an MCP server (step 4.5, [ADR 0019](adr/0019-mcp-server-with-the-users-own-permissions.md)): an AI client can search and act on Dispatch with a dispatcher's own permissions.

- Endpoint: `http://localhost:8080/api/mcp` (streamable HTTP, stateless)
- Auth: a Keycloak access token as `Authorization: Bearer ...`. Discovery for OAuth clients: `/.well-known/oauth-protected-resource`.

## Tools

| Tool | Does | Changes anything? |
|---|---|---|
| `search_work_orders` | The company's work orders, newest first, optionally one status; pages of up to 50 | no |
| `get_work_order` | One work order in full | no |
| `list_sla_breaches` | Work waiting for a technician that the SLA policy widened or escalated | no |
| `suggest_technicians` | Who Dispatch would offer the job to now, with each part of the score | no |
| `reassign_work_order` | Gives an assigned job that hasn't started to another verified technician with the skills | only on the second call, with the confirmation token from the first |

Text that people wrote (titles, descriptions, notes) comes back marked as data. Every call is recorded in `ai_tool_call`.

## Connect a client

Get a token (local Keycloak, test user; tokens last 5 minutes):

```bash
export TOKEN=$(curl -s http://localhost:8180/realms/dispatch/protocol/openid-connect/token \
  -d grant_type=password -d client_id=dispatch-dev-cli -d username=dispatcher.meghna -d password=password | jq -r .access_token)
```

Claude Code:

```bash
claude mcp add --transport http dispatch http://localhost:8080/api/mcp --header "Authorization: Bearer $TOKEN"
```

MCP Inspector: transport "Streamable HTTP", URL `http://localhost:8080/api/mcp`, header `Authorization: Bearer <token>`.

Then ask, for example: "Which jobs are late?", "Who would you suggest for the vault camera job?", "Give it to Sumon instead, Rahim is on another emergency." The client shows the preview and asks before the reassignment happens.

## Scripted demo

`./scripts/demo-claude.sh` runs Claude Code in print mode, connected only to Dispatch (`--strict-mcp-config`), allowed only Dispatch's tools, signed in as `dispatcher.meghna`: it suggests technicians, previews a reassignment and asks, confirms only in the next turn when told yes, and summarizes a job whose description tries to give it orders. It needs Claude Code signed in (`claude auth login`) and uses your Claude account.

## Raw protocol (curl)

```bash
curl -s -X POST http://localhost:8080/api/mcp -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"list_sla_breaches","arguments":{}}}'
```
