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
| A work order has at most one open or captured payment | partial unique index `payment_one_live_per_work_order` (V19) |
| Every journal entry balances (debits = credits, at least two lines) | deferred constraint triggers `journal_entry_balanced`, `journal_line_balanced`, checked at commit (V20, [ADR 0017](../adr/0017-double-entry-ledger-in-postgresql.md)) |
| The ledger is append-only | `UPDATE`, `DELETE` and `TRUNCATE` revoked from the app role, plus triggers that refuse them for everyone (V20) |

`company` and `technician` are not tenant-owned: companies are the tenant list, and technicians work for many companies.

## Events, AI and matching (Phases 2 and 3)

Kept apart from the diagram above so each stays readable. Row-level security applies to every table with a `tenant_id`.

```mermaid
erDiagram
  company ||--o{ ai_call : "owns (tenant_id)"
  work_order ||--o{ completion_audit : "is checked by"
  technician ||--o| technician_embedding : "is described by"
  work_order ||--o| work_order_embedding : "is described by"

  event_publication {
    uuid id PK
    text listener_id "which listener still has to run"
    text event_type
    text serialized_event
    timestamptz publication_date
    timestamptz completion_date "null until the listener succeeded"
    text status
    int completion_attempts
  }
  ai_call {
    bigint id PK
    uuid tenant_id FK
    text feature "skill-suggestions, intake, completion-audit"
    text prompt_version
    text model
    text outcome "OK, ERROR, INVALID_OUTPUT, REPLAYED, RATE_LIMITED, CIRCUIT_OPEN, BUDGET_EXCEEDED"
    text input_redacted "no emails, phones or street addresses"
    text output
    int input_tokens
    int output_tokens
    numeric cost_usd "at the list price when made"
    int latency_ms
    timestamptz created_at
  }
  completion_audit {
    uuid id PK
    uuid tenant_id FK
    uuid work_order_id FK
    uuid source_event_id UK "the SUBMITTED event: audited once"
    text state "NEEDS_REVIEW, AUTO_APPROVED, APPROVED, REJECTED, DECIDED_ELSEWHERE"
    text model
    text prompt_version
    jsonb items "per checklist item: verdict and evidence"
    numeric confidence
    text recommendation "APPROVE or REVIEW"
    text decided_by "reviewer id, system, or work order"
    timestamptz decided_at
  }
  technician_embedding {
    uuid technician_id PK
    vector embedding "768 dimensions, HNSW index"
    text model
    text source_hash "re-embed only when the text changed"
  }
  work_order_embedding {
    uuid work_order_id PK
    uuid tenant_id FK
    vector embedding
    text model
    text source_hash
  }
```

## Money (Phase 4)

Payments, refunds and payouts record what happened at the provider and the bank; the ledger records where the money is. Journal tables are append-only (V20).

```mermaid
erDiagram
  work_order ||--o{ payment : "is funded by (one live)"
  payment ||--o| refund : "is returned by"
  work_order ||--o| payout : "pays the technician"
  technician ||--o{ payout : "receives"
  payment ||--o{ payment_mismatch : "disagrees with the provider"
  company ||--o{ idempotency_key : "owns (tenant_id)"
  journal_entry ||--|{ journal_line : "has (debits = credits)"
  ledger_account ||--o{ journal_line : "is posted to"
  journal_entry |o--o| journal_entry : "reverses"

  payment {
    uuid id PK
    uuid tenant_id FK
    uuid work_order_id FK "partial UK: one INITIATED, PENDING or CAPTURED"
    text tran_id UK "our id at the provider, 30 chars max"
    text status "INITIATED, PENDING, CAPTURED, FAILED, EXPIRED"
    bigint amount_minor
    text currency
    text gateway_url "the payment page"
    text val_id UK "the provider's validation id"
    text bank_tran_id "refunds name it"
    bigint store_amount_minor "what the gateway pays us"
    smallint risk_level
    text reason
    timestamptz settled_at
  }
  refund {
    uuid id PK
    uuid tenant_id FK
    uuid payment_id FK, UK
    bigint amount_minor
    text refund_trans_id UK "our id for the refund"
    text refund_ref_id "the provider's"
    text status "REQUESTED, PROCESSING, REFUNDED, FAILED"
    text reason
  }
  payout {
    uuid id PK
    uuid tenant_id FK
    uuid work_order_id FK, UK
    uuid technician_id FK
    bigint amount_minor "budget minus the platform fee"
    text status "PENDING, PAID"
    text bank_reference
    timestamptz paid_at
  }
  payment_mismatch {
    bigint id PK
    uuid tenant_id FK
    uuid payment_id FK
    text local_status
    text provider_status
    text detail "for a person: refund it, check it"
  }
  idempotency_key {
    uuid tenant_id PK
    text idem_key PK
    text scope
    text request_hash
    text status "IN_PROGRESS, COMPLETED"
    int response_status
    jsonb response_body
  }
  ledger_account {
    text code PK "bank, gateway_clearing, client_escrow, technician_payable, platform_revenue, gateway_fees"
    text kind "ASSET, LIABILITY, REVENUE, EXPENSE"
  }
  journal_entry {
    uuid id PK
    uuid tenant_id FK
    text reference "UK per company: capture:<payment>, approval:<work order>"
    text kind "CAPTURE, GATEWAY_FEE, APPROVAL, PAYOUT, REFUND, REVERSAL"
    uuid work_order_id
    text currency
    uuid reverses_entry_id FK "UK: reversed at most once"
  }
  journal_line {
    bigint id PK
    uuid entry_id FK
    uuid tenant_id FK
    text account_code FK
    uuid technician_id FK "for technician_payable"
    bigint debit_minor "exactly one side positive"
    bigint credit_minor
  }
```
