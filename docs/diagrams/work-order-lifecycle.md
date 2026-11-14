# Work order lifecycle

Source of truth: `Transition` in the `workorder` module. `WorkOrderTransitionsTest` checks every command from every status against this table. Why it's hand-written: [ADR 0004](../adr/0004-hand-written-state-machine.md).

```mermaid
stateDiagram-v2
  [*] --> DRAFT: create
  DRAFT --> PUBLISHED: publish (needs a checklist)
  PUBLISHED --> ROUTED: route (offers sent)
  ROUTED --> PUBLISHED: offers expired
  ROUTED --> ASSIGNED: assign (technician accepted)
  ASSIGNED --> IN_PROGRESS: start
  IN_PROGRESS --> SUBMITTED: submit
  SUBMITTED --> APPROVED: approve
  SUBMITTED --> IN_PROGRESS: reject (with reason)
  APPROVED --> PAID: payout released
  DRAFT --> CANCELLED: cancel
  PUBLISHED --> CANCELLED: cancel
  ROUTED --> CANCELLED: cancel
  ASSIGNED --> CANCELLED: cancel
  PAID --> [*]
  CANCELLED --> [*]
```

| Command | From | To | Who triggers it | API |
|---|---|---|---|---|
| publish | DRAFT | PUBLISHED | Dispatcher, client admin | `POST /api/work-orders/{id}/publish` (then routes, see next row) |
| route | PUBLISHED | ROUTED | Publishing, when technicians in range have the skills: offers go to the nearest 10 | (part of publish) |
| offers expired | ROUTED | PUBLISHED | Scheduled job (step 2.5) | - |
| assign | ROUTED | ASSIGNED | Technician accepts an offer; the first one wins ([ADR 0005](../adr/0005-conditional-update-for-job-acceptance.md)) | `POST /api/offers/{id}/accept` |
| start | ASSIGNED | IN_PROGRESS | The assigned technician | `POST /api/work-orders/{id}/start` |
| submit | IN_PROGRESS | SUBMITTED | The assigned technician | `POST /api/work-orders/{id}/submit` |
| approve | SUBMITTED | APPROVED | Dispatcher, client admin | `POST /api/work-orders/{id}/approve` |
| reject | SUBMITTED | IN_PROGRESS | Dispatcher, client admin | `POST /api/work-orders/{id}/reject` |
| mark paid | APPROVED | PAID | Payment (Phase 4) | - |
| cancel | DRAFT, PUBLISHED, ROUTED, ASSIGNED | CANCELLED | Dispatcher, client admin | `POST /api/work-orders/{id}/cancel` |

Anything else gets **409** with Problem Details, for example:

```json
{
  "type": "urn:dispatch:problem:invalid-transition",
  "title": "Not allowed in the current state",
  "status": 409,
  "detail": "Can't approve a work order that is CANCELLED",
  "currentStatus": "CANCELLED",
  "action": "approve"
}
```

## A request through the layers

```mermaid
sequenceDiagram
  participant D as Dispatcher
  participant C as WorkOrderController
  participant S as WorkOrderService
  participant W as WorkOrder (aggregate)
  participant DB as PostgreSQL
  D->>C: POST /api/work-orders/42/approve + JWT
  C->>S: approve(42)
  Note over S: @PreAuthorize: CLIENT_ADMIN or DISPATCHER
  S->>DB: begin, set app.tenant_id, select work order 42
  DB-->>S: row (only if it is the caller's tenant)
  S->>W: approve()
  W->>W: Transition table: SUBMITTED -> APPROVED allowed?
  alt not allowed
    W-->>S: InvalidTransitionException
    S-->>D: 409 Problem Details
  else allowed
    W->>W: status = APPROVED, record WorkOrderStatusChanged
    S->>DB: UPDATE ... WHERE id = 42 AND version = 7
    Note over DB: 0 rows if someone changed it meanwhile: 409 concurrent-update
    S-->>D: 200, new ETag "8"
  end
```

## Editing a draft safely

Edits (`PATCH /api/work-orders/{id}`) need `If-Match` with the ETag from your last read:

- Missing `If-Match`: **428**.
- Someone saved in between: **412**. Reload and edit again.
- The work order isn't a draft any more: **409**.

## Two technicians accept at once

```mermaid
sequenceDiagram
  participant R as Rahim
  participant K as Karim
  participant DB as PostgreSQL
  R->>DB: UPDATE work_order SET status=ASSIGNED, technician=Rahim WHERE id=7 AND status=ROUTED
  Note over DB: row 7 locked by Rahim's transaction
  K->>DB: same UPDATE with technician=Karim
  Note over K,DB: waits for the row lock
  R->>DB: offer ACCEPTED, other offers EXPIRED, COMMIT
  DB-->>R: 1 row: 200, the job is Rahim's
  Note over DB: Karim's UPDATE re-checks WHERE on the committed row: status is ASSIGNED
  DB-->>K: 0 rows: 409 already-taken
```
