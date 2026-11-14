# 7. Server-sent events for live dashboard updates, not WebSocket

Date: 2026-11-15
Status: Accepted

## Context

Dispatchers keep the work order list open all day. When a technician accepts a job or submits the work, the row should change on their screen within a second, without a page reload. Every change is already on Kafka (`dispatch.workorder.events.v1`, ADR 0006). The question is how to get it from the server to the browser.

The traffic is **one way**: the server tells the browser what changed. The browser's own actions (approve, reject, cancel) are ordinary REST calls that already exist, with their own validation, `If-Match` and Problem Details.

## Decision

**Server-sent events (SSE)**: `GET /api/live/work-orders` returns `text/event-stream`, one `work-order.status-changed` event per change, for the caller's company only.

- **Same auth as every call.** Bearer token, `CLIENT_ADMIN` or `DISPATCHER`, tenant from the token. The browser's `EventSource` can't set headers, so the dashboard reads the stream with `fetch` (or a fetch-based SSE client).
- **Every instance sees every event.** Each app instance holds only its own connections, so its live listener uses a consumer group of its own (random id) and starts at the latest offset. A shared group would hand each instance a share of the partitions, and a dispatcher connected to instance A would miss events instance B read.
- **Connections are short-lived on purpose.** 30-minute timeout, `retry: 3000`. The client reconnects, which also lets a load balancer move it. Keep-alive comments every 20 s stop proxies from closing idle streams and find dead ones. `X-Accel-Buffering: no` stops nginx from buffering.
- **No replay.** A reconnecting dashboard reloads the list once. Live updates are for "now"; the REST list is the source of truth. Each event carries its `eventId` as the SSE `id`, so replay from `Last-Event-ID` can be added later.
- **Best effort.** The listener never throws: a malformed event is skipped here (the notification consumer dead-letters it). Missing a live update costs a stale row until the next reload, never data.

## Alternatives considered

| Option | Verdict |
|---|---|
| **WebSocket** | Two-way, which we don't need. Its own protocol upgrade (some proxies and corporate networks block or time it out), no automatic reconnect, and authentication, message format and heartbeats are all ours to design. Worth it for chat or collaborative editing, where the browser sends a stream too. |
| **Polling every few seconds** | Simplest, works everywhere. But N dashboards × one query every 5 s, mostly answering "nothing changed", and still up to 5 s late. Fine as a fallback. |
| **Long polling** | Lower latency than polling, but one request per event and fiddly to get right; SSE is the standard version of the same idea. |
| **Shared consumer group** | Each instance would get only some partitions: dispatchers on one instance would miss changes read by another. |
| **In-process events instead of Kafka** | Only the instance that made the change would know. Wrong as soon as there are two instances. |

## Consequences

Good:
- Plain HTTP: works through proxies and load balancers, automatic reconnect, `curl -N` shows it.
- Nothing new to secure: the same token, role and tenant checks as the rest of the API.
- Scales with instances: each one only fans out to its own connections.

Bad / accepted costs:
- One open HTTP connection per dashboard. Spring MVC's `SseEmitter` doesn't hold a thread while idle, but each connection holds a socket and some memory.
- Events are sent on the Kafka listener thread: a client that stops reading can slow the others once its socket buffer fills (shortcut in `LiveUpdates`; a queue per connection is the fix).
- HTTP/1.1 browsers allow about 6 connections per host; HTTP/2 removes that limit.
- A consumer group per instance: on each restart Kafka keeps an empty group until its offsets expire (a week).
