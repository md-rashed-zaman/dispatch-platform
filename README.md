# Dispatch

A multi-tenant marketplace for on-site technical work. A bank needs a POS terminal fixed at its Gulshan branch; Dispatch finds verified technicians nearby with the right skills, offers them the job, makes sure exactly one gets it when several accept at once, collects photo proof straight into object storage, and lets the bank approve the work. Payment, AI checks of completed work and an ops assistant come in later phases.

Built as a portfolio project to show production backend engineering: correctness under concurrency, tenant isolation, measured performance, and failure handling, each proven by tests and written up.

> **Status:** Phase 1 complete (core marketplace). Phase 2 in progress: events, reliability and observability.

## What it does today

| | How | Proof |
|---|---|---|
| **First accept wins** | 50 technicians accept the same job at the same instant: exactly one gets it, the rest get 409. A conditional `UPDATE ... WHERE status = 'ROUTED'` decides inside Postgres, with two more layers behind it. | [ADR 0005](docs/adr/0005-conditional-update-for-job-acceptance.md), `FirstAcceptWinsTests` |
| **Tenant isolation** | Shared schema. Hibernate filters every query by tenant, and Postgres row-level security filters again, so a bug in one layer can't leak another company's data. Technicians see only their own offers. | [ADR 0003](docs/adr/0003-shared-schema-and-row-level-security-for-tenancy.md), `TenantIsolationTests` |
| **Geo search in under a millisecond** | "Verified technicians with these skills within 20 km, nearest first" over 50,000 technicians: 29.5 ms with no indexes, **0.8 ms** after rewriting one condition the planner misjudged (estimated 5 rows, actually 22,500). | [docs/perf/geo-search.md](docs/perf/geo-search.md) |
| **Lifecycle that can't be broken** | A ten-row transition table; every command from every status is tested (90 pairs). Edits need `If-Match`, so nobody overwrites changes they haven't seen (412). | [ADR 0004](docs/adr/0004-hand-written-state-machine.md), [lifecycle](docs/diagrams/work-order-lifecycle.md) |
| **Photos without touching the API** | Presigned URLs signed for one key, content type and size, valid 5 minutes. Confirm checks what arrived, down to the first bytes. Same code on SeaweedFS locally and Amazon S3. | [upload flow](docs/diagrams/attachment-upload.md), `AttachmentApiTests` |
| **Events that can't get lost** | Every lifecycle change goes to Kafka through a transactional outbox: the event row commits with the change, and a relay sends it. Kafka down, or the app killed with `kill -9`: requests keep succeeding and every event arrives later, in order per work order. | [ADR 0006](docs/adr/0006-polling-outbox-before-cdc.md), `OutboxRelayTests` |
| **Consumers that survive real delivery** | The notification consumer deduplicates by event id in the same transaction as its work, retries temporary failures with exponential backoff, and moves poison messages to a dead-letter topic without stopping. Replaying the whole topic notified nobody twice. | `CompanyNotificationsTests`, [failure modes](docs/failure-modes.md) |
| **Live dashboard, email that can wait** | Dispatchers get every change of their company's work orders over server-sent events within a second; every app instance reads every event, so it works behind a load balancer. Emails are queued, sent through a circuit breaker, and retried for hours: with the mail server down, the API doesn't slow down and no email is lost. | [ADR 0007](docs/adr/0007-sse-over-websocket.md), `LiveUpdatesTests`, `EmailSenderTests` |
| **Jobs that run once on many instances** | Work orders nobody takes are offered farther away after 2 hours and escalated to the company after 6. ShedLock runs each round on one instance; every step is also guarded by state and version, so even two runs at once change each work order once. Two instances and a load balancer locally: `--profile cluster`. | `SlaPolicyTests`, [failure modes](docs/failure-modes.md) |
| **Fails politely** | Database or storage down: 503 with `Retry-After` within 3 s, readiness `DOWN`, automatic recovery. Every error is RFC 9457 Problem Details. | [docs/failure-modes.md](docs/failure-modes.md) |

## Architecture

**System context** (C4 level 1):

```mermaid
flowchart LR
  dispatcher["Dispatcher / client admin<br/>company staff"]
  technician["Technician<br/>mobile app"]
  ops["Platform admin"]
  dispatch["<b>Dispatch</b><br/>posts jobs, matches technicians,<br/>assigns, collects proof"]
  keycloak["Keycloak<br/>everyone logs in here"]
  storage["Object storage<br/>photos and documents"]
  dispatcher -- "creates, publishes, approves" --> dispatch
  technician -- "accepts offers, works jobs" --> dispatch
  ops -- "onboards companies" --> dispatch
  technician -- "uploads photos directly" --> storage
  dispatch -- "verifies tokens" --> keycloak
  dispatch -- "signs URLs, checks uploads" --> storage
```

**Containers** (C4 level 2):

```mermaid
flowchart LR
  web["Dispatcher web app<br/>(later)"]
  phone["Technician app"]
  api["<b>Dispatch API</b><br/>Spring Boot modular monolith, Java 21<br/>modules: identity, tenancy, workorder,<br/>technician, matching, notification,<br/>media, outbox, web"]
  pg[("PostgreSQL 17<br/>PostGIS, pgvector,<br/>row-level security")]
  kc["Keycloak 26<br/>OAuth2 / OIDC"]
  s3[("SeaweedFS<br/>S3 API")]
  kafka[["Kafka 4 (KRaft)<br/>dispatch.workorder.events.v1"]]
  web -- "REST + JWT" --> api
  phone -- "REST + JWT" --> api
  phone -- "PUT bytes, presigned" --> s3
  api -- "JDBC as dispatch_app,<br/>so RLS applies" --> pg
  api -- "fetches signing keys" --> kc
  api -- "HEAD, ranged GET, DELETE" --> s3
  api -- "outbox relay:<br/>work order events" --> kafka
```

Modules talk through public APIs and events only; Spring Modulith fails the build on a boundary violation. Workorder never calls matching: matching reacts to `WorkOrderPublished` after the commit, and notification to `WorkOrderAssigned`. Other systems get every status change from Kafka, keyed by work order id; notification itself reads them from there to tell the company, as a separate service would. The [context map](docs/diagrams/context-map.md) shows the planned modules (matching, payment, ledger, notification, AI) and the events between them.

**From publish to accept:**

```mermaid
sequenceDiagram
  participant D as Dispatcher
  participant API as Dispatch API
  participant DB as PostgreSQL
  participant T as Technicians
  D->>API: POST /work-orders/7/publish
  API->>DB: DRAFT -> PUBLISHED, record WorkOrderPublished (same transaction)
  API-->>D: 200 PUBLISHED
  Note over API: after commit, on another thread: matching reacts to the event
  API->>DB: search: verified, all skills, within 20 km, nearest 10
  API->>DB: offers for the 10, work order ROUTED
  T->>API: GET /me/offers (row-level security: only their own)
  T->>API: POST /offers/{id}/accept (several at once)
  API->>DB: UPDATE work_order SET status=ASSIGNED, technician=me WHERE id=7 AND status=ROUTED
  Note over DB: first statement wins the row lock,<br/>the others re-check WHERE after it commits and change 0 rows
  API->>DB: winner's offer ACCEPTED, the other 9 EXPIRED (same transaction)
  API-->>T: winner 200, everyone else 409 already-taken
```

Stack: Spring Boot 4.1, Spring Modulith 2.1, Hibernate 7, Flyway, PostgreSQL 17 + PostGIS + pgvector, Keycloak 26, Kafka 4 (KRaft) with Spring for Apache Kafka, Resilience4j, Thymeleaf email templates, Mailpit, AWS SDK v2 against SeaweedFS, Testcontainers, JUnit 5, Spotless, JaCoCo, GitHub Actions.

## Documentation

- Decisions: [docs/adr](docs/adr) (one per significant choice, with the alternatives)
- Diagrams: [context map](docs/diagrams/context-map.md), [ERD](docs/diagrams/erd.md), [lifecycle](docs/diagrams/work-order-lifecycle.md), [upload flow](docs/diagrams/attachment-upload.md)
- Measurements: [docs/perf](docs/perf)
- Operations: [docs/failure-modes.md](docs/failure-modes.md)
- Domain: [event storming](docs/domain/event-storming.md), [glossary](docs/glossary.md)

## Getting started

Requirements: Docker, JDK 21+.

```bash
cp .env.example .env
docker compose -f infra/compose/docker-compose.yml up -d --wait postgres keycloak seaweedfs kafka mailpit
cd app && ./mvnw spring-boot:run
```

Optional sample data (3 companies, 50,000 technicians across Bangladesh, 30 work orders around Dhaka and Chattogram; wipes existing data, takes a few seconds):

```bash
docker compose -f infra/compose/docker-compose.yml exec -T postgres psql -U dispatch -d dispatch -v ON_ERROR_STOP=1 < infra/seed/dev-seed.sql
```

- Health: http://localhost:8080/actuator/health
- API docs: http://localhost:8080/swagger-ui/index.html (click **Authorize**, log in as a test user below)
- Keycloak admin console: http://localhost:8180 (admin / admin)
- Object storage (S3 API): http://localhost:8333, bucket `dispatch-media` (created by the app on startup); keys in `infra/compose/seaweedfs/s3.json`
- Two instances behind a load balancer: `cd app && ./mvnw package -DskipTests`, stop the app on 8080, then `docker compose -f infra/compose/docker-compose.yml --profile cluster up -d --build` (http://localhost:8088; Linux host networking)
- Mailpit (every email the app sends, nothing leaves the machine): http://localhost:8025
- Live updates: `curl -N -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/live/work-orders`
- Kafka: `localhost:9092`, topic `dispatch.workorder.events.v1` (created by the app on startup). Kafka UI: `docker compose -f infra/compose/docker-compose.yml --profile tools up -d kafka-ui`, then http://localhost:8090

Test users (password `password` for all; local realm only, defined in `infra/compose/keycloak/dispatch-realm.json`):

| Username | Role | Tenant |
|---|---|---|
| `dispatcher.meghna` | DISPATCHER | Meghna Bank |
| `admin.meghna` | CLIENT_ADMIN | Meghna Bank |
| `dispatcher.shwapno` | DISPATCHER | Shwapno Retail |
| `tech.rahim` | TECHNICIAN | none (marketplace-wide); lives in Gulshan, so work orders published nearby are offered to this account |
| `ops.admin` | PLATFORM_ADMIN | none |

Get a token from the command line (the `dispatch-dev-cli` client allows password login for local testing only):

```bash
TOKEN=$(curl -s http://localhost:8180/realms/dispatch/protocol/openid-connect/token \
  -d grant_type=password -d client_id=dispatch-dev-cli -d username=dispatcher.meghna -d password=password | jq -r .access_token)
curl -s localhost:8080/api/me -H "Authorization: Bearer $TOKEN"
```

Tests use Testcontainers and the same Postgres image, so build it once first:

```bash
docker compose -f infra/compose/docker-compose.yml build postgres
cd app && ./mvnw verify
```

`verify` also checks formatting (Spotless, Palantir style) and writes a coverage report to `app/target/site/jacoco/index.html`. Fix formatting with `./mvnw spotless:apply`. CI runs the same build on every push and pull request.

## Demo

With the stack running and the seed loaded, one script walks through the whole story with real logins: publish, offers, a double-tapped accept, start, a photo upload straight to storage, submit, approve, and another company getting 404.

```bash
./scripts/demo.sh
```

Press Enter between steps, or run it straight through with `DEMO_FAST=1`. A 3-minute talk track to record alongside it: [docs/demo.md](docs/demo.md).

## Repository layout

```
app/              Spring Boot modular monolith
infra/compose/    Local Docker Compose stack
infra/helm/       Kubernetes Helm chart (later)
load-tests/       k6 scripts
evals/            AI evaluation datasets and results
docs/adr/         Architecture decision records
docs/diagrams/    Context map, ERD, sequence diagrams
docs/learning/    Learning journal and open questions
docs/runbooks/    Operational runbooks and postmortems
scripts/          Demo script
docs/perf/        Performance measurements
```
