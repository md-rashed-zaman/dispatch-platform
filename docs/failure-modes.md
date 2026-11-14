# Failure modes

What happens when a part of Dispatch fails, measured on the local stack (step 1.8) by stopping each container while the app ran. Update this file when a dependency is added or a behavior changes. Phase 2 adds Kafka and Redis.

## Dependencies

| Down | What users see | Recovery | Measured |
|---|---|---|---|
| **PostgreSQL** | Every API call answers **503** `unavailable` with `Retry-After: 5`, within 3 s. Readiness turns `DOWN`, so a load balancer stops sending traffic; liveness stays `UP`, because restarting the app wouldn't bring the database back. | Automatic: the first request after Postgres returns succeeds. | Before the fix: each request hung 30 s (the pool's default wait), then a bare 500, and readiness stayed `UP`. |
| **Keycloak** | Tokens already issued keep working until they expire (5 minutes): the app checks signatures locally with the keys it fetched at startup. New logins fail. | Automatic. | Existing token: 200. New login: connection refused. |
| **Keycloak, and the app restarts meanwhile** | Every request gets **401**: the restarted app can't fetch the signing keys. | Automatic once Keycloak is back (same token: 200 again). | Kubernetes would restart pods during an identity outage only on a deploy or a crash; avoid deploying while Keycloak is down. |
| **Object storage** | Confirming an upload answers **503** `unavailable`. Asking for an upload URL and listing files still work: signing a URL is local math, but the URLs are useless until storage is back. The rest of the API is unaffected. | Retry the upload and confirm. Pending uploads that never finish are cleaned up after an hour. | Before the fix: confirm gave a bare 500. |
| **Technician search slow** | Publishing waits for it (routing runs inside publish). | - | The search takes under 1 ms on 50,000 technicians (docs/perf/geo-search.md). Moves to an event listener in Phase 2. |

## Races and retries

| Situation | What happens | Proven by |
|---|---|---|
| 50 technicians accept the same job at once | Exactly one 200; 49 × 409 `already-taken`; the database agrees. | `FirstAcceptWinsTests` ([ADR 0005](adr/0005-conditional-update-for-job-acceptance.md)) |
| The winner's phone retries (response lost) | 200 again, same offer. | `OfferApiTests` |
| The winner double-taps (both requests arrive together) | Both 200. | `FirstAcceptWinsTests` |
| Two dispatchers edit the same draft | The second save gets 412 (`If-Match` with a stale ETag). Two saves in the same instant: the slower gets 409. | `WorkOrderApiTests` |
| The app crashes mid-accept | The transaction rolls back: the work order stays `ROUTED`, offers unchanged. Nothing half-done. | Postgres transactions |
| Several app instances run the upload cleanup | Each deletes what's still there; deleting what's gone does nothing. Wasteful, not harmful (ShedLock in Phase 2). | Design |

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
