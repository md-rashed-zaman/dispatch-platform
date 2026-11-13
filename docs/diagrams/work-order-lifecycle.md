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
| publish | DRAFT | PUBLISHED | Dispatcher, client admin | `POST /api/work-orders/{id}/publish` |
| route | PUBLISHED | ROUTED | Matching (step 1.6) | - |
| offers expired | ROUTED | PUBLISHED | Scheduled job (step 1.6) | - |
| assign | ROUTED | ASSIGNED | Technician accepts an offer (step 1.6) | - |
| start | ASSIGNED | IN_PROGRESS | Technician (step 1.6) | - |
| submit | IN_PROGRESS | SUBMITTED | Technician (step 1.7) | - |
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
