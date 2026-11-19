# 18. Feature flags in PostgreSQL behind the OpenFeature API, with hash bucketing

Date: 2026-11-19
Status: Accepted

## Context

AI auto-approval (step 3.5) saves reviewers time but carries risk: a false approval pays for work not done. It should reach companies gradually, be measured against companies without it, and be switched off for everyone in seconds if the guardrail moves. Until now it was one global setting (`dispatch.review.auto-approve`).

## Decision

1. **A `flags` module** with flags in PostgreSQL (V21): a kill switch (`enabled`), a rollout percentage, an allow list and a deny list of companies. Platform admins change them through `PUT /api/flags/{key}` with `If-Match` (412 on a stale copy) and a required reason; every change is kept with its before and after (`feature_flag_change`).
2. **Evaluation order:** off for everyone if disabled; off on the deny list; on on the allow list; otherwise on if the company's bucket is below the percentage.
3. **Buckets by hash:** `SHA-256(flagKey + ":" + companyId) mod 100`. The same company is always in the same bucket on every instance and every request; raising 20% to 50% only adds companies; each flag picks a different 20%.
4. **Read through OpenFeature.** Code asks an OpenFeature `Client` (the company id is the targeting key); `DispatchFlagProvider` answers from an in-memory snapshot refreshed every 5 seconds, so a flag check costs a map lookup. If the database can't be read, the last snapshot stays.
5. **The experiment's arm is stored with each audit** (`completion_audit.auto_approve_variant`, plus whether the AI would have approved), so results compare what happened to each job, not today's flag. `GET /api/experiments/auto-approve` and two Grafana panels compare the arms.

## Alternatives considered

| Option | Verdict |
|---|---|
| A flag vendor (LaunchDarkly, Optimizely, Flagsmith, Unleash) | The right choice for many teams; not needed for one experiment, and it would leave the core idea (bucketing, kill switch, audit) unbuilt. OpenFeature keeps the door open: switching is registering their provider. |
| `Math.random() < 0.2` per request | Rejected: a company would flip arms between jobs, which ruins both the experience and the measurement. |
| Hash of the company only (no flag key) | Rejected: the same 20% of companies would get every experiment, so experiments would interfere. |
| Per-instance config (application.yml) | Rejected: a restart per change, no audit, no per-company targeting. Kept only as the global kill switch above the flag. |
| Read the flag from the database on every evaluation | Rejected: a query per check; with the snapshot a change reaches every instance within 5 seconds, which is fast enough for a kill switch. |

## Consequences

- OpenFeature's API is a global object per JVM; each application context registers its provider under its own domain, so test contexts with their own databases don't read each other's flags.
- Flags are platform data, not a company's: no row-level security, platform admins only. Reports across companies go through a SECURITY DEFINER function that returns counts and times per arm only.
