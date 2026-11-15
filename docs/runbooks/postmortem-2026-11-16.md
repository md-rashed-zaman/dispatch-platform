# Postmortem: a table lock took down the whole API (chaos day, 2026-11-16)

**Status:** resolved, action items done. **Blameless:** this is about the system, not the person who held the lock.

## Summary

On chaos day we ran three planned failures against the local stack under steady traffic (`scripts/traffic.sh`: a work order created, published and accepted about every 2.5 s, plus list and profile reads).

**Kafka** stopped for two minutes and **Redis** for two minutes: no errors. Both behaved as designed (below).

**A lock on `work_order`**, held by an admin session, was the real incident:
- Every work order create hung, each holding a database connection.
- After ten of them the connection pool was empty, and **every endpoint answered 503 for five minutes**, including reads that never touch the lock.
- **Nothing paged.** It was spotted in the traffic log, after 10 minutes.

Two fixes:
- **Database timeouts for the app's role:** a blocked request now gives up after 5 s and frees its connection.
- **A new alert on server errors.**

The same failure, repeated with the fixes, stayed limited to the blocked writes, and the new alert fired.

## Impact (the lock, before the fixes)

| | |
|---|---|
| Duration | 10 min 35 s (02:55:23 to 03:05:58), of which 5 min total outage (03:01 to 03:05:57) |
| Creates | all failed: 9 hung 30 s until the client gave up, then 503 |
| Reads (list, profile) | worked for 5.5 minutes, then 503 like everything else once the pool was gone |
| Accepts | none possible (no new work orders) |
| Data | nothing lost or corrupted: blocked transactions never committed |

## Timeline (local time)

| Time | Event |
|---|---|
| 02:46:50 | Traffic starts. Baseline: 90 requests, all 2xx; accept p50 30 ms |
| 02:48:12 | **Experiment 1:** Kafka stopped |
| 02:50:15 | 153 events waiting in the outbox, oldest 124 s. All requests still 2xx (305 during the outage, accept p50 18 ms) |
| 02:50:30 | Kafka healthy again; outbox drained within the same second; 71 emails sent for the work assigned meanwhile |
| 02:52:04 | **Experiment 2:** Redis stopped. No errors (239 requests). Profile reads p50 312 ms instead of 6 ms (cache timeout, then database); rate limiting off (fail open) |
| 02:54:06 | Redis back; profile reads cached again |
| 02:55:23 | **Experiment 3:** an admin session runs `LOCK TABLE work_order IN EXCLUSIVE MODE` and keeps the transaction open |
| 02:55:54 | First create times out at the client (30 s). The request is still waiting in the server, holding a connection |
| 02:55 to 03:01 | Every 38 s another create hangs; reads still fine (the lock allows reads) |
| ~03:01 | Tenth hung request: the pool (10 connections) is empty. Every endpoint now waits 3 s for a connection and answers 503 |
| 03:05:36 | Noticed in the traffic log: everything 503. **No alert fired** |
| 03:05:54 | Runbook step 3: `pg_stat_activity` shows the admin session in a transaction for 10 min 30 s, with nine app sessions waiting on a `relation` lock behind it. `pg_cancel_backend` on the admin session |
| 03:05:58 | Admin session gone, no waiting sessions, traffic back to 2xx within 4 s |

## Root cause

Why did every endpoint fail?
1. The connection pool was empty: all ten connections belonged to requests waiting for the lock.
2. They waited without limit, because the app's database role had no `lock_timeout` or `statement_timeout`.
3. The client gave up after 30 s, but the server doesn't notice a client leaving while it waits on the database, so the request, and its connection, stayed.
4. New requests waited 3 s for a connection (Hikari's `connection-timeout`, set in step 1.8) and failed with 503.

**The trigger was a long-held lock. The root cause was that one slow resource could hold every connection without limit.** A slow query, a stuck migration or a forgotten `BEGIN` in a psql session would have done the same.

Why didn't anything page? The only alert watched accept latency, computed from requests that finish. Hung requests never finish, failed creates aren't accepts, and when nothing is accepted the latency SLI has no data at all. The dashboard's 5xx panel showed it, but nobody watches dashboards at night.

## What went well

- Kafka and Redis outages were non-events, as designed: outbox and queued email kept everything; cache and rate limiter stepped aside. The outbox age panel showed the Kafka backlog building and draining.
- No data was lost or corrupted in any experiment.
- The runbook's `pg_stat_activity` query found the blocker immediately.
- Recovery needed no restart: the app was healthy 4 s after the lock went.

## What went poorly

- One lock took down every endpoint (cascading failure through the shared pool).
- No alert; detection took 10 minutes, and only because someone was looking.
- The runbook said `pg_cancel_backend` without saying when it isn't enough. We tested it:
  - **Enough:** when the blocker is running a statement. The statement is cancelled, and an aborted transaction releases its locks at once.
  - **Not enough:** when the blocker is `idle in transaction`, because there's nothing to cancel. That case needs `pg_terminate_backend`.

## Where we got lucky

The lock was `EXCLUSIVE`, which still allows reads, so reads kept working for 5.5 minutes before the pool ran out. An `ACCESS EXCLUSIVE` lock (what most `ALTER TABLE` statements take) blocks reads too: the pool would have been gone in seconds.

## Action items

| # | Action | Status |
|---|---|---|
| 1 | Role timeouts for `dispatch_app` (migration V14): `lock_timeout` 5 s, `statement_timeout` 15 s, `idle_in_transaction_session_timeout` 60 s. Migrations and admin sessions (other roles) unaffected; the 50-technician accept race still passes | Done |
| 2 | Lock and statement timeouts answer 503 with `Retry-After` (Problem Details), like other dependency failures | Done |
| 3 | Alert "API answering with server errors": more than 5% 5xx over 5 minutes, NaN-safe, with its own runbook (`api-errors.md`) | Done |
| 4 | Runbooks: when to cancel and when to terminate a blocking session; the `pg_blocking_pids` query | Done |
| 5 | `DatabaseLockTimeoutTests`: with the table locked by another session, a create answers 503 within 4 to 8 s | Done |
| 6 | Traffic script bug found on the way: after a timed-out call it reused the previous response (published a technician id, 404). Fixed | Done |
| 7 | Detection took 4 min 51 s in the rerun (5-minute window plus `for: 2m`). Consider a faster, higher-threshold rule for total outages | Open |
| 8 | Migrations should set their own `lock_timeout` so a migration waiting for a lock doesn't block everything queued behind it | Open (no risky migration yet) |

## Verification: the same failure with the fixes (03:13:49 to 03:18:55)

| | Before the fixes | After |
|---|---|---|
| Creates while locked | hung 30 s, then 503 after 3 s | 503 in 5.0 s (p50 5026 ms), 43 of them |
| Reads while locked | failed once the pool was gone | 84 of 84 fine: list p50 14 ms, profile p50 8 ms |
| Alert | none | "API answering with server errors" fired at 03:18:40 (23.6% 5xx), cleared at 03:21:38 |
| Recovery after `pg_cancel_backend` | 4 s | 2 s (first create 201 at 03:18:57), no restart |
