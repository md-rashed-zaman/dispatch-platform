# Failure modes

What happens when a part of Dispatch fails, measured on the local stack by stopping each container while the app ran (step 1.8), and again under steady traffic on chaos day (step 2.8, [postmortem](runbooks/postmortem-2026-11-16.md)). Update this file when a dependency is added or a behavior changes. Kafka was added in step 2.2; Redis comes later in Phase 2.

## Dependencies

| Down | What users see | Recovery | Measured |
|---|---|---|---|
| **PostgreSQL** | Every API call answers **503** `unavailable` with `Retry-After: 5`, within 3 s. Readiness turns `DOWN`, so a load balancer stops sending traffic; liveness stays `UP`, because restarting the app wouldn't bring the database back. | Automatic: the first request after Postgres returns succeeds. | Before the fix: each request hung 30 s (the pool's default wait), then a bare 500, and readiness stayed `UP`. |
| **Keycloak** | Tokens already issued keep working until they expire (5 minutes): the app checks signatures locally with the keys it fetched at startup. New logins fail. | Automatic. | Existing token: 200. New login: connection refused. |
| **Keycloak, and the app restarts meanwhile** | Every request gets **401**: the restarted app can't fetch the signing keys. | Automatic once Keycloak is back (same token: 200 again). | Kubernetes would restart pods during an identity outage only on a deploy or a crash; avoid deploying while Keycloak is down. |
| **Object storage** | Confirming an upload answers **503** `unavailable`. Asking for an upload URL and listing files still work: signing a URL is local math, but the URLs are useless until storage is back. The rest of the API is unaffected. | Retry the upload and confirm. Pending uploads that never finish are cleaned up after an hour. | Before the fix: confirm gave a bare 500. |
| **Kafka** | Nothing: every request succeeds as usual. Events wait in `outbox_event`; the relay logs one warning with the cause, then a reminder every 30 s. | Automatic: the relay sends the backlog, oldest first, as soon as Kafka is back. | Kafka stopped, a work order published (2 events), the app killed with `kill -9`, Kafka started, the app started: both events arrived, in order, on one partition. Without the outbox (a direct send after commit), the same run returns 200 and the events are gone for good. `OutboxRelayTests` |
| **Mail server** | Nothing: requests never send email. Emails wait in `outgoing_email`. After a few failures the SMTP circuit breaker opens, and each send round then takes milliseconds instead of a timeout per email. Health stays `UP`: nothing users do depends on email. | Automatic: the breaker lets one trial email through every 30 s; once it works, the queue drains. An email is tried for about three hours (10 attempts), then marked failed and logged. | Mailpit stopped, the demo run 6 times: every run passed, health stayed `UP`, 12 emails queued. The breaker opened after 2 emails failed (the other 10 were never tried), a half-open trial failed, and the next one 30 s later closed it: all 12 delivered within 30 s of Mailpit's return. `EmailSenderTests` |
| **Redis** | Nothing breaks: every request is served and health stays `UP`. Technician profiles come from the database, about 0.3 s slower each (every cache call waits for the 300 ms Redis timeout). Requests aren't rate limited (fail open); a circuit breaker skips Redis after a few failures, so limiting costs nothing meanwhile. | Automatic: the breaker tries Redis again every 10 s. Buckets start full again (Redis keeps nothing on restart). | Redis stopped: 8 profile reads 200 in 0.3 to 0.7 s, 150 API calls all 200, health `UP`; restarted: limiting back. `TechnicianProfileCacheTests`, `RateLimitTests` |
| **Observability stack (Grafana LGTM)** | Nothing: requests, health and every other part work as usual. Metrics and traces for that period are dropped (pushed, not buffered). | Automatic: export resumes when it's back. The exporters' own logging is off, because it repeats a stack trace every few seconds. | Stopped: 5 requests 200 in 30 to 80 ms, health `UP` |
| **Database lock held by someone else** (an admin session, a migration) | Requests that need the locked table answer **503** with `Retry-After` after 5 s (`lock_timeout`, V14) and free their connection; everything else keeps working. The "API answering with server errors" alert pages within about 5 minutes. | Find and end the blocker ([api-errors runbook](runbooks/api-errors.md)): `pg_cancel_backend` if it's running a statement, `pg_terminate_backend` if it's idle in a transaction. The app recovers on its own. | Chaos day ([postmortem](runbooks/postmortem-2026-11-16.md)): before V14, one lock hung every create and, once the 10 connections were held, took down every endpoint for 5 minutes without an alert. After: 43 creates got 503 in 5.0 s, 84 of 84 reads fine, alert fired, recovery 2 s after the cancel. `DatabaseLockTimeoutTests` |
| **AI provider (Gemini)**: down, overloaded (503), hanging, or a bad key | Nothing breaks: `POST /api/ai/skill-suggestions` answers **200** with `available: false`, and the form says "Suggestions aren't available right now. Type the skills yourself." Health stays `UP`. With the provider hanging, the answer takes up to about 21 s (two models time out at 10 s each) and the form stays usable meanwhile. | Automatic. 503 and timeouts are retried and fall back to the second model; a model failing half its calls is skipped for 30 s (circuit breaker), so later requests answer fast. A bad key isn't retried. | Real free tier: a timeout and a 503 on Flash-Lite, answered by `gemini-3.1-flash-lite`; the breaker then skipped Flash-Lite and answers came in 1.2 to 2.4 s. Bad key: fallback in 0.2 s (warm). Endpoint blocked (connection refused): fallback in 4.9 s. Blackholed: 20.2 s, both models tried. `AiGatewayTests` ([ADR 0012](adr/0012-ai-gateway-in-front-of-model-providers.md)) |
| **Matching fails or the app crashes after a publish** | The work order stays PUBLISHED; nobody gets offers yet. Publishing itself isn't affected: matching runs after the commit, on another thread. | The event stays incomplete in Spring Modulith's registry and is delivered again on the next startup. Routing a work order that's no longer PUBLISHED does nothing, so a second delivery is safe. | `PublishRoutingTests`, `AssignmentNotificationsTests` (step 2.1) |

## Races and retries

| Situation | What happens | Proven by |
|---|---|---|
| 50 technicians accept the same job at once | Exactly one 200; 49 × 409 `already-taken`; the database agrees. | `FirstAcceptWinsTests` ([ADR 0005](adr/0005-conditional-update-for-job-acceptance.md)) |
| The winner's phone retries (response lost) | 200 again, same offer. | `OfferApiTests` |
| The winner double-taps (both requests arrive together) | Both 200. | `FirstAcceptWinsTests` |
| Two dispatchers edit the same draft | The second save gets 412 (`If-Match` with a stale ETag). Two saves in the same instant: the slower gets 409. | `WorkOrderApiTests` |
| The same event is delivered twice (consumer crash before its offset commit, a rebalance, an outbox resend) | Processed once: `processed_event` is written in the same transaction as the work, so the second delivery is skipped. Rewinding the notification group to the start of the topic re-read every event and notified nobody again. | `CompanyNotificationsTests` (T19) |
| A malformed event on the topic | Sent to `dispatch.workorder.events.v1.notification-dlt` at once (retrying can't fix it), with the cause in its headers and an ERROR log line; the consumer carries on. | `CompanyNotificationsTests`, local stack |
| Notifying fails for a while (mail server down) | Retried in place after 1, 2, 4 and 8 s, holding back only that partition. Then the dead-letter topic, and the consumer carries on. | `CompanyNotificationsTests` |
| The app crashes mid-accept | The transaction rolls back: the work order stays `ROUTED`, offers unchanged. Nothing half-done. | Postgres transactions |
| Several app instances run the scheduled jobs | ShedLock: each round of the SLA policy and the cleanup jobs runs on one instance; the others skip it. If that instance dies, the lock expires (`lockAtMostFor`) and another takes over. | `SlaPolicyTests`; two-instance cluster: only one instance ran rounds |
| Two instances run the SLA policy at once anyway (a round outlives its lock) | Each work order is still escalated once: the step checks its stage, and `@Version` lets only one save win. | `SlaPolicyTests` (with and without the lock); cluster rebuilt without the lock: both ran every round, one breach each |

## Data protection under failure

| Situation | What stops it |
|---|---|
| A bug skips Hibernate's tenant filter | Row-level security still filters every query (`TenantIsolationTests`). |
| Application code assigns two technicians | The partial unique index allows one accepted offer per work order. |
| An upload doesn't match what was declared | Storage refuses it (signed type and length); confirm checks size, type and first bytes, and deletes the file. |
| A download link leaks | It expires after 5 minutes; the bucket is private. |

## Known gaps

- No retries or circuit breaker toward storage: a slow (not down) storage makes confirms slow. Phase 2 adds timeouts and Resilience4j.
- No rate limiting: one client can exhaust the connection pool. Phase 2 (Redis).
- Offers expire lazily; the sweep that returns unanswered work orders to `PUBLISHED` comes in step 2.5.
