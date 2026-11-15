-- Timeouts for the application's database role (step 2.8, docs/runbooks/postmortem-2026-11-16.md).
-- Chaos day: an admin session held a lock on work_order. Every request that needed it waited forever, each one
-- holding a pooled connection; after ten of them the pool was empty and every endpoint failed. With these, a
-- blocked request gives up quickly, frees its connection, and the API answers 503 instead of hanging.
-- They apply to new connections of dispatch_app only: migrations (run as the owner) and admin sessions are
-- unaffected. Legitimate lock waits are short: 50 technicians racing for one job queue a few milliseconds each, so
-- 5 s leaves room even on a slow machine.
ALTER ROLE dispatch_app SET lock_timeout = '5s';
ALTER ROLE dispatch_app SET statement_timeout = '15s';
-- A transaction left open (a bug, a stuck thread) can't hold locks for longer than this.
ALTER ROLE dispatch_app SET idle_in_transaction_session_timeout = '60s';
