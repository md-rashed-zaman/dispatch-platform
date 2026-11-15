# Runbook: the API is answering with server errors

**Alert:** "API answering with server errors". More than 5% of API responses were 5xx over the last 5 minutes, for 2 minutes.

**Added after:** chaos day, [postmortem 2026-11-16](postmortem-2026-11-16.md). Every endpoint answered 503 for five minutes and nothing paged, because the only alert watched accept latency, and failed requests never became slow accepts.

**Dashboard:** Grafana, *Dispatch overview*: "Requests per second by status", "Server errors (5xx) share", "Slowest endpoints".

## 1. Which errors? (1 minute)

- **503 `unavailable`** (most likely): a dependency or the database is out of reach or too busy. The app is protecting itself; see step 2.
- **500 `internal-error`**: a bug. Find the stack trace in the logs (search for the response's time, or its `traceId` in Tempo), and check what was deployed last.

## 2. 503s: what is the app waiting for?

The 503 detail says which dependency ("the database", "storage"). For the database:

```sql
-- Sessions waiting on locks, and who holds them
select a.pid, a.application_name, a.state, a.wait_event_type, now() - a.xact_start as in_transaction,
       pg_blocking_pids(a.pid) as blocked_by, left(a.query, 80) as query
from pg_stat_activity a
where a.datname = 'dispatch' and (a.wait_event_type = 'Lock' or a.state like 'idle in transaction%')
order by in_transaction desc;
```

- **App sessions waiting on a lock, all blocked by one pid:** someone holds a table or row lock (a migration, a script, an admin session). Look at that pid's `application_name` and `query`.
  - If its state is `active` (running a statement): `select pg_cancel_backend(<pid>);` cancels the statement. An aborted transaction releases its locks at once.
  - If it's `idle in transaction`: there's no statement to cancel. Use `select pg_terminate_backend(<pid>);`.
- **No lock waits, but `waiting for a connection` errors:** the pool (10 connections) is exhausted. Since V14 a blocked statement gives up after 5 s (`lock_timeout`) and any statement after 15 s (`statement_timeout`), so the pool frees itself; if it doesn't, look for long transactions.

## 3. After

The app recovers by itself once the cause is gone: no restart needed. Check the error share returns to 0 and the alert resolves. If the cause was a person or a script holding locks, the action item is a guard for it (run migrations with their own `lock_timeout`, keep admin transactions short).
