# Dispatch

Multi-tenant field service marketplace. Companies post on-site jobs (fix a POS terminal, a printer, a network). Dispatch finds qualified technicians nearby, assigns the job safely when several accept at once, checks the completed work with AI, and pays the technician.

> Status: early development (Phase 0: setup and domain discovery).

## Planned architecture

- Modular monolith (Spring Boot, Spring Modulith) with modules per bounded context: `identity`, `tenancy`, `workorder`, `technician`, `matching`, `media`, `notification`, `payment`, `ledger`, `flags`, `ai`.
- PostgreSQL with PostGIS (geo search) and pgvector (semantic matching), row-level security for tenant isolation.
- Keycloak (OAuth2 / OIDC), Kafka (events via transactional outbox), Redis (cache, rate limits), S3-compatible object storage.
- AI through the Gemini API behind an AI gateway module: structured intake parsing, semantic matching, completion audit with human review, MCP tools for an ops assistant.
- Observability with OpenTelemetry and Grafana.

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
docs/perf/        Performance measurements
```

## Getting started

Not runnable yet. Setup instructions arrive with step 0.3 (skeleton app and local infrastructure).

## Decisions

See [docs/adr](docs/adr).
