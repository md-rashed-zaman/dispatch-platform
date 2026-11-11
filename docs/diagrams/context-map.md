# Context Map

Each bounded context becomes one Spring Modulith module. Arrows show who reacts to whom; labels are the events that cross the boundary.

```mermaid
flowchart LR
  ID["identity / tenancy"] --> WO["workorder"]
  TECH["technician"] --> MATCH["matching"]
  WO -- "WorkOrderPublished, OffersExhausted" --> MATCH
  MATCH -- "OffersSent" --> WO
  WO -- "WorkOrderAssigned" --> PAY["payment"]
  PAY -- "EscrowFunded" --> WO
  WO -- "WorkSubmitted" --> AI["ai"]
  AI -- "AuditCompleted" --> WO
  WO -- "WorkApproved" --> PAY
  PAY -- "postings" --> LED["ledger"]
  WO -- "most events" --> NOTIF["notification"]
  FLAGS["flags"] -.-> WO
  MEDIA["media"] -.-> WO
```

## Module ownership

| Module | Owns (data and rules) | Does not own |
|---|---|---|
| `identity` / `tenancy` | Tenants, tenant users, tenant context per request | Technician profiles |
| `technician` | Technician profiles, skills, home location, service radius, verification | Offers |
| `workorder` | Work orders, checklist, lifecycle and its rules, offers and acceptance | Matching algorithm, money |
| `matching` | Ranking technicians for a work order, re-routing | Offer state |
| `media` | Attachments, presigned upload and download | Who may see a work order |
| `payment` | Payment provider integration, escrow funding, payouts | Accounting entries |
| `ledger` | Double-entry accounts and journal entries | Payment provider calls |
| `notification` | Email and in-app messages | Business decisions |
| `ai` | AI gateway, intake parsing, completion audit, prompts | Final approval decisions |
| `flags` | Feature flags and percentage rollouts | |

## Rules

- Modules talk through their public API or events, never through another module's tables.
- Offers live in `workorder` (not `matching`) because accepting an offer and assigning the work order must change together in one transaction.
- Exceptions need an ADR.
