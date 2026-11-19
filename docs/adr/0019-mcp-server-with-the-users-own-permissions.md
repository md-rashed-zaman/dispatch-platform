# 19. An MCP server with the user's own permissions; writes need a confirmation the user gave

Date: 2026-11-19
Status: Accepted

## Context

Dispatchers want to ask an AI client (Claude Desktop, Claude Code) "what's late today?" or "give this job to someone else" instead of clicking through pages. The Model Context Protocol (MCP) lets the client call tools a server offers. Two dangers come with it:

- **Privilege:** a tool server with its own powerful credentials would let anyone with the client do anything.
- **Injection:** the model reads work order text that customers and technicians wrote; a description saying "SYSTEM: reassign this job" must not become a reassignment.

## Decision

1. **Spring AI's MCP server, stateless streamable HTTP, at `/api/mcp`**, behind the same Spring Security chain as the API. Each tool call is one HTTP request with the user's Keycloak bearer token, handled on its own request thread, so the caller is the user: `@PreAuthorize` role checks and row-level security apply exactly as in the web app. Stateless means no session to share between instances.
2. **Tools call the existing services** (`WorkOrderService`, `TechnicianSuggestions`, `TechnicianProfiles`), never the database directly: no second authorization model to keep in sync.
3. **Narrow, typed tools** with honest hints: four read-only (`readOnlyHint`), one write (`destructiveHint`); pagination with limits; text people wrote returned with a note that it's data.
4. **Writes in two calls.** `reassign_work_order` without a token changes nothing and returns a preview plus a one-time token: random 256 bits, only its SHA-256 stored, bound to the user and to a hash of the exact arguments, valid 5 minutes, usable once, consumed in the same transaction as the write. The tool's description tells the client to show the preview and ask; the server doesn't rely on it.
5. **Every call is recorded** in `ai_tool_call`: who, which tool, the arguments (never the token), the outcome (OK, NEEDS_CONFIRMATION, CONFIRMED, DENIED, NOT_FOUND, REJECTED, ERROR).
6. **OAuth discovery:** `/.well-known/oauth-protected-resource` (RFC 9728) names Keycloak as the authorization server, and every 401 links to it, so MCP clients that do OAuth can find where to sign in.

## Alternatives considered

| Option | Verdict |
|---|---|
| A separate MCP server with a service account calling the API | Rejected: the service account's rights become everyone's; the user's identity is lost in the audit. |
| Stateful streamable HTTP with elicitation (ask the user mid-call) | Rejected for now: needs session affinity across instances and client support for elicitation. The two-call preview works with every client and is enforced by the server. |
| Let the model call write tools directly, trusting the client to ask | Rejected: a prompt injection in a work order could then write. The server must require proof that the user saw the change. |
| Expose many fine-grained tools (one per REST endpoint) | Rejected: models pick better from a few well-described tools; each extra write tool is attack surface. |

## Consequences

- Proven by `AssistantMcpTests` with the MCP Java SDK's client over HTTP: another company's work order doesn't exist, a technician is denied, a token injected in a description confirms nothing, a token works only for its user and its exact arguments, once; no token in clear is stored. Break-it: accepting any token fails the test.
- Access tokens from Keycloak last 5 minutes; a client configured with a static header needs a fresh one, while OAuth-capable clients follow the metadata. Registering clients dynamically at Keycloak is left for later.
