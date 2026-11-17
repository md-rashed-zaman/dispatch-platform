# Entity relationship diagram

Source of truth: the Flyway migrations in `app/src/main/resources/db/migration`. Update this file in the same pull request as any schema change.

```mermaid
erDiagram
  company ||--o{ work_order : "owns (tenant_id)"
  work_order ||--|{ checklist_item : "has"
  work_order ||--o{ offer : "is offered via"
  technician ||--o{ offer : "receives"
  technician |o--o{ work_order : "is assigned (technician_id)"
  work_order ||--o{ attachment : "has photos"
  company ||--o{ attachment : "owns (tenant_id)"

  company {
    uuid id PK
    text name
    text slug UK
    text notification_email "nullable: no emails without it"
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
    text rejection_reason
    timestamptz published_at "the SLA clock"
    text sla_stage "ON_TRACK, WIDENED, ESCALATED"
    bigint version
    timestamptz created_at
  }
  checklist_item {
    uuid id PK
    uuid work_order_id FK
    int position "unique per work order"
    text description
  }
  offer {
    uuid id PK
    uuid tenant_id FK "always the work order's tenant"
    uuid work_order_id FK
    uuid technician_id FK
    text status
    timestamptz expires_at
    timestamptz responded_at
  }
  attachment {
    uuid id PK
    uuid tenant_id FK
    uuid work_order_id FK
    text object_key UK "tenants/{tenant}/work-orders/{id}/{uuid}"
    text file_name
    text content_type
    bigint declared_size "what the presigned URL allows"
    bigint size "what arrived, set on confirm"
    text status "PENDING, CONFIRMED"
    uuid uploaded_by
    bigint version
  }
```

## Infrastructure tables

Not domain data: no tenant, no row-level security, no foreign keys. Each serves the reliability machinery of Phase 2.

```mermaid
erDiagram
  outbox_event {
    bigint id PK "send order"
    uuid event_id UK "consumers deduplicate on it"
    text topic
    text message_key "Kafka partition key: the work order id"
    text event_type
    jsonb payload "the whole envelope"
    text trace_parent "W3C trace context of the writing request"
    timestamptz created_at
    timestamptz published_at "null until Kafka confirmed it"
  }
  processed_event {
    text consumer PK
    uuid event_id PK
    timestamptz processed_at
  }
  outgoing_email {
    bigint id PK
    uuid message_id UK "the event it's about"
    text recipient
    text subject
    text text_body
    text html_body
    int attempts
    timestamptz next_attempt_at
    text last_error
    timestamptz sent_at
    timestamptz failed_at
  }
  shedlock {
    varchar name PK "one row per scheduled job"
    timestamptz lock_until
    timestamptz locked_at
    varchar locked_by "instance name"
  }
```

| Table | Written by | Read by | ADR / topic |
|---|---|---|---|
| `outbox_event` | the business transaction (`Outbox.append`) | `OutboxRelay`, which sends to Kafka | [ADR 0006](../adr/0006-polling-outbox-before-cdc.md), T17 |
| `processed_event` | each Kafka consumer, with its work | the same consumer, to skip duplicates | [ADR 0008](../adr/0008-idempotent-consumers-retries-and-dead-letters.md), T19 |
| `outgoing_email` | the notification consumer | `EmailSender`, through a circuit breaker | T20 |
| `shedlock` | ShedLock, around scheduled jobs | ShedLock on every instance | [ADR 0009](../adr/0009-scheduled-jobs-run-once-with-shedlock-and-idempotent-steps.md), T22 |
| `event_publication` | Spring Modulith, with in-process events | Spring Modulith, to redeliver incomplete events | T16 |

## Aggregates

| Aggregate (root) | Tables | Module |
|---|---|---|
| `Company` | `company` | `tenancy` |
| `Technician` | `technician` | `technician` |
| `WorkOrder` | `work_order`, `checklist_item` | `workorder` |
| `Offer` | `offer` | `workorder` |
| `Attachment` | `attachment` | `media` |

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
| A tenant sees and writes only its own work orders, checklists and offers | row-level security policies `tenant_isolation` (V4, [ADR 0003](../adr/0003-shared-schema-and-row-level-security-for-tenancy.md)) |
| A technician reads only their own offers and the work orders offered or assigned to them | row-level security policies `technician_reads_own_offers`, `technician_reads_offered_work` (V7, [ADR 0005](../adr/0005-conditional-update-for-job-acceptance.md)) |
| An offer's tenant is its work order's tenant | composite foreign key `offer_work_order_same_tenant` (V7) |

`company` and `technician` are not tenant-owned: companies are the tenant list, and technicians work for many companies.
