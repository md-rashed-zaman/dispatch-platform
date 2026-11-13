# Entity relationship diagram

Source of truth: the Flyway migrations in `app/src/main/resources/db/migration`. Update this file in the same pull request as any schema change.

```mermaid
erDiagram
  company ||--o{ work_order : "owns (tenant_id)"
  work_order ||--|{ checklist_item : "has"
  work_order ||--o{ offer : "is offered via"
  technician ||--o{ offer : "receives"
  technician |o--o{ work_order : "is assigned (technician_id)"

  company {
    uuid id PK
    text name
    text slug UK
    timestamptz created_at
  }
  technician {
    uuid id PK
    text full_name
    text phone UK
    text_array skills
    float home_latitude
    float home_longitude
    geography home_location "generated from lat/lon"
    int service_radius_km "1 to 100"
    numeric rating "1.00 to 5.00, nullable"
    text status
    bigint version
  }
  work_order {
    uuid id PK
    uuid tenant_id FK
    text title
    text status "CHECK: 9 states"
    text site_address
    float site_latitude
    float site_longitude
    geography site_location "generated from lat/lon"
    text_array required_skills
    timestamptz window_starts_at
    timestamptz window_ends_at
    bigint budget_minor "poisha, > 0"
    text budget_currency
    uuid technician_id FK "required from ASSIGNED on"
    bigint version
  }
  checklist_item {
    uuid id PK
    uuid work_order_id FK
    int position "unique per work order"
    text description
  }
  offer {
    uuid id PK
    uuid work_order_id FK
    uuid technician_id FK
    text status
    timestamptz expires_at
    timestamptz responded_at
  }
```

## Aggregates

| Aggregate (root) | Tables | Module |
|---|---|---|
| `Company` | `company` | `tenancy` |
| `Technician` | `technician` | `technician` |
| `WorkOrder` | `work_order`, `checklist_item` | `workorder` |
| `Offer` | `offer` | `workorder` |

Aggregates point at each other with ID columns, never with Java object references (see [ADR 0002](../adr/0002-aggregates-reference-each-other-by-id.md)). Foreign keys still exist in the database: an ID reference in code is a design choice, but data integrity is non-negotiable.

## Rules the database enforces

| Rule | How |
|---|---|
| Status is one of the known values | `CHECK (status IN (...))` |
| An assigned (or later) work order has a technician | `work_order_assigned_has_technician` |
| At most one accepted offer per work order | partial unique index `offer_one_accepted_per_work_order` |
| A technician gets at most one offer per work order | `offer_once_per_technician` |
| Money is positive and in minor units | `budget_minor BIGINT CHECK (> 0)` |
| Time window ends after it starts | `work_order_window_valid` |
