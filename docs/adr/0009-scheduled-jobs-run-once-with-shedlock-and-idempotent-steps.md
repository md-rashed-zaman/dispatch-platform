# 9. Scheduled jobs run once with ShedLock, and every step is safe to repeat

Date: 2026-11-15
Status: Accepted

## Context

Spring's `@Scheduled` runs on every app instance. With two instances, the SLA policy ("escalate work orders nobody took for 6 hours") would escalate each one twice, and the bank would get two emails. Some jobs should run on one instance per round; others (a queue) should run on all.

## Decision

- **ShedLock** (JDBC provider, `shedlock` table, database time, `locked_by` = instance name) on jobs that should run once per round: the SLA policy and the three cleanups. The instance that takes the job's row runs it; the others skip the round. `lockAtMostFor` bounds how long a dead instance's lock lasts; `lockAtLeastFor` stops a slightly late instance from running the same round again.
- **Every locked job is also idempotent**, because a lock can expire under a slow run: each SLA step is a `WorkOrder` method that acts only in the right status and stage, saved with `@Version`. Two runs at once change each work order once.
- **Queues aren't locked:** the email sender takes rows with `FOR UPDATE SKIP LOCKED`, so every instance helps. The outbox relay has its own advisory lock to keep event order. SSE keep-alive is per instance.

## Alternatives considered

| Option | Verdict |
|---|---|
| Quartz in cluster mode | Persistent triggers, misfire handling, many jobs managed at runtime. More than five periodic jobs need. |
| Only one instance runs jobs (a flag) | That instance is special: if it's down, nothing runs. |
| External scheduler (Kubernetes CronJob) | Good for batch jobs; these need the app's code and transactions, and run every minute. |
| Lock only, no idempotency | Correct until the first slow run outlives the lock. |

## Consequences

Good: proven on a two-instance cluster (`--profile cluster`): with the lock, one instance ran each round, and each breach produced one event and one email. Rebuilt without the lock, both ran every round, and the state checks still kept it to one breach each. `SlaPolicyTests` covers both, plus a break-it (no lock and no stage check: duplicates).

Bad / accepted: a lock row per job in the database; jobs must be written to be repeatable; a round can be skipped entirely if the holder dies mid-run (the next round catches up).
