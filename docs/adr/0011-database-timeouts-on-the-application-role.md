# 11. Lock and statement timeouts on the application's database role

Date: 2026-11-16
Status: Accepted

## Context

Chaos day ([postmortem](../runbooks/postmortem-2026-11-16.md)): an admin session held a lock on `work_order`.
- Every request that needed the table waited without limit, each holding one of the pool's ten connections.
- After ten of them, every endpoint, including reads that never touched the lock, answered 503 for five minutes.

Hikari's 3 s `connection-timeout` (step 1.8) only limits waiting *for* a connection, not what happens once a request has one.

## Decision

Set the timeouts on the `dispatch_app` role (migration V14), so they apply to every connection the app opens, whatever code runs on it:

| Setting | Value | Stops |
|---|---|---|
| `lock_timeout` | 5 s | a request waiting on someone else's lock, holding a connection |
| `statement_timeout` | 15 s | a runaway query |
| `idle_in_transaction_session_timeout` | 60 s | a transaction left open by a bug, holding locks |

Lock and statement timeouts answer 503 with `Retry-After` (Problem Details), like other dependency failures. Migrations and admin sessions run as other roles and are unaffected.

5 s, not 2 s: legitimate waits are short but real. Fifty technicians accepting one job queue on its row, a few milliseconds each, and `FirstAcceptWinsTests` must keep passing on a slow CI machine.

## Alternatives considered

| Option | Verdict |
|---|---|
| Timeouts per query in code (`@QueryHints`, `SET LOCAL`) | Every query must remember it; the one that doesn't is the one that hangs. |
| Bigger connection pool | Delays the same failure; more connections waiting on the same lock. |
| Hikari leak detection | Logs a warning; doesn't free anything. |
| Only fix the trigger (no long admin locks) | The next slow query or stuck migration does the same. |

## Consequences

Good: the same failure repeated with V14: 43 blocked creates answered 503 in 5.0 s, all 84 reads in the same window succeeded, and the new 5xx alert fired. `DatabaseLockTimeoutTests` guards it.

Bad / accepted: a legitimately long statement (a big report later) must run under another role or raise its own timeout; a request that waits 5 s still fails, it just fails without taking others with it.
