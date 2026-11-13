# 4. Hand-written state machine for the work order lifecycle

Date: 2026-11-14
Status: Accepted

## Context

A work order moves through nine statuses, from DRAFT to PAID or CANCELLED. Money depends on getting this right: approving a cancelled work order pays a technician for nothing, and starting one that was never assigned breaks the marketplace. Several code paths will change the status: the REST API, offer acceptance (step 1.6), payouts, scheduled jobs, the AI completion audit, the ops assistant's MCP tools.

The rules are fixed by the business. Users don't configure them.

## Decision

- **One `Transition` table** (an enum) lists every command, the statuses it may start from, and the status it leads to. It is keyed by command, not by target status, because `start` (from ASSIGNED) and `reject` (from SUBMITTED) both lead to IN_PROGRESS.
- **One method per command on the `WorkOrder` aggregate** (`publish()`, `assign()`, `approve()`, ...). Each one checks the table and throws `InvalidTransitionException` (HTTP 409) on a move the table doesn't list. There is no status setter, so every path goes through the same rules.
- **Guards beyond the status** live in the same methods. Examples: publish needs a checklist, reject needs a reason.
- **Each transition records a `WorkOrderStatusChanged` event.** Spring Data publishes it when the work order is saved.
- **The API exposes commands as actions** (`POST /api/work-orders/{id}/approve`), never `PATCH {"status": ...}`.
- **The spec is a test:** every command is tried from every status (90 pairs) against an explicit table written in the test.
- **The database also has a CHECK constraint** on the status column, as a last line of defense.

## Alternatives considered

- **Spring Statemachine.** It adds configuration, its own persistence of machine state, and a learning curve, for ten fixed transitions. It earns its keep when users design workflows at runtime (like configurable approval pipelines), which Dispatch doesn't need.
- **Status checks in services** (`if (wo.getStatus() == SUBMITTED) wo.setStatus(APPROVED)`). Every new caller would have to repeat them, and one that forgets breaks the rules. Rejected: the aggregate must protect itself.
- **`PATCH {"status": "APPROVED"}`.** The client picks the status and the rules hide behind a generic update. Rejected for the same reason.

## Consequences

Good:
- The whole lifecycle is readable in one table of ten lines, and the diagram in [docs/diagrams/work-order-lifecycle.md](../diagrams/work-order-lifecycle.md) matches it.
- Loosening any guard fails the transition spec immediately. For example, allowing approve from CANCELLED fails 2 tests.
- Adding a state is a change to one enum, one method and one row in the test table.

Bad / accepted costs:
- New transitions need code and a deploy, not configuration. That's fine for rules owned by the product, not by users.
- Retries aren't idempotent yet: repeating `publish` after it succeeded gets a 409. Idempotency keys come with payments (T19).
- Cancelling after work has started isn't supported. It needs a business process (who pays for the visit?), not a click.
