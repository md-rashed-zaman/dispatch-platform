# Runbook: accepts are slow

**Alert:** "Accept latency SLO burning fast". More than 14.4% of `POST /api/offers/{id}/accept` took over 300 ms, over both the last 5 minutes and the last hour. At that rate the month's error budget (1% of accepts may be slow) is gone in about two days.

**Why it matters:** technicians race to accept a job; a slow accept means a technician waits, taps again, or loses a job they were first to. The first-accept-wins guarantee (ADR 0005) still holds when it's slow; only the experience suffers.

**Dashboard:** Grafana, *Dispatch overview* (http://localhost:3000 locally): "Accept latency", "Slowest endpoints", "Accept conflicts per minute", "Requests per second by status".

## 1. Confirm and size it (2 minutes)

1. "Accept latency": is p99 alone high (a few slow requests), or p50 too (everything slow)?
2. "Requests per second by status": more traffic than usual? Many 409s (a popular job, everyone racing)?
3. "Slowest endpoints": only accept, or every endpoint? **Everything slow** points to shared resources (database, CPU, GC). **Only accept** points to the accept path itself.
4. Did it start with a deploy? Compare the start of the slowdown with the deploy time.

## 2. Find the slow part (5 minutes)

Open a slow request's trace: Explore, Tempo, TraceQL:

```
{ name = "http post /api/offers/{id}/accept" && duration > 300ms }
```

Look at which span takes the time:

| Slow span | Likely cause | Next step |
|---|---|---|
| A database statement on `work_order` (the conditional UPDATE) | Row lock contention: many technicians accepting the same job queue on one row | Check "Accept conflicts per minute". Expected for a very popular job; each waits for the one before. If constant, check for a long transaction holding the row (step 3). |
| Waiting for a connection (time before the first statement) | Connection pool exhausted (Hikari, 10 connections) | Look for slow queries elsewhere holding connections, or a traffic spike. |
| `security filterchain` / `authenticate bearertoken` | Keycloak's signing keys being refetched, or CPU | Rare: keys are cached. Check CPU and GC. |
| Nothing slow inside, but long overall | CPU saturation or GC pauses | JVM dashboard (shipped with the stack): GC pause time, CPU. |

## 3. Database checks

```sql
-- Who is waiting on whom (blocked accepts show up here)
select pid, wait_event_type, wait_event, state, now() - xact_start as in_transaction, left(query, 80)
from pg_stat_activity where datname = 'dispatch' and state <> 'idle' order by in_transaction desc;

-- Long transactions holding locks
select pid, now() - xact_start as age, left(query, 80) from pg_stat_activity
where xact_start < now() - interval '5 seconds' order by age desc;
```

A transaction open for seconds while holding a `work_order` row lock blocks every accept for that job. Find what it is (a job, a migration, an admin session). Ending it (`select pg_cancel_backend(pid)`) releases the queue.

## 4. Mitigate

- **Bad deploy:** roll back first, investigate after.
- **Lock contention on one hot job:** expected and self-limiting; the queue drains as offers close. Nothing to do unless it's constant.
- **Pool exhausted by something else:** find and stop the slow caller; the pool is shared.
- **CPU or GC:** scale out (the API is stateless: `--profile cluster` locally) and look at what changed.

## 5. Afterwards

Note the cause, how long budget was burning, and whether the alert fired early enough. If the cause was new, add a row to the table in step 2.
