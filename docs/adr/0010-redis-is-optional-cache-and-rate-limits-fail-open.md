# 10. Redis is optional: the cache and the rate limiter step aside when it's down

Date: 2026-11-16
Status: Accepted

## Context

Step 2.6 added Redis for two things: a shared cache for technician profiles, and per-company rate limits that every app instance enforces together. Both make the API better; neither is needed to answer a request correctly. If Redis being down took the API down, Redis would be a new single point of failure for something users don't need.

## Decision

- **Cache-aside, with fallback.** `@Cacheable(sync = true)` on profile reads; JSON values, TTL 10 minutes ± 10% jitter; evicted after an update commits (`TechnicianUpdated`). `LoggingCacheErrorHandler`: a Redis error is logged and the read goes to the database. Redis command timeout 300 ms.
- **Rate limits fail open.** Bucket4j token bucket per company (burst 100, 100 a minute), compare-and-swap in Redis. When Redis can't be asked, the request is let through. A Resilience4j circuit breaker skips Redis after a few failures, so requests don't each wait for a timeout. Over the limit: 429 Problem Details with `Retry-After`.
- **Health doesn't include Redis.** The Redis health indicator is off; readiness covers the database only.
- **No startup dependency.** The rate limiter connects on first use and reads its settings then (servlet filters are created before the rest of the app).

## Alternatives considered

| Option | Verdict |
|---|---|
| Fail closed (reject when the limiter can't count) | Turns a Redis outage into an API outage: the attacker's goal, achieved by our own infrastructure. Right for login throttling, not for a B2B API. |
| In-memory rate limits per instance | N instances let a client through N times the limit, and answers differ per instance. |
| Local cache (Caffeine) | Each instance has its own copy, so an update must reach every instance to evict. |
| Redis in readiness | A Redis outage would take every instance out of the load balancer. |

## Consequences

Good: measured with Redis stopped under traffic (chaos day and step 2.6): every request served, health `UP`; limiting resumed when Redis returned. `TechnicianProfileCacheTests` and `RateLimitTests` pause Redis and check both.

Bad / accepted: while Redis is down, every profile read pays the 300 ms Redis timeout (the cache has no circuit breaker, unlike the limiter), and nobody is rate limited. Buckets restart full after a Redis restart (no persistence).
