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

Requirements: Docker, JDK 21+.

```bash
cp .env.example .env
docker compose -f infra/compose/docker-compose.yml up -d --wait postgres keycloak
cd app && ./mvnw spring-boot:run
```

Optional sample data (3 companies, 300 technicians, 30 work orders around Dhaka and Chattogram; wipes existing data):

```bash
docker compose -f infra/compose/docker-compose.yml exec -T postgres psql -U dispatch -d dispatch -v ON_ERROR_STOP=1 < infra/seed/dev-seed.sql
```

- Health: http://localhost:8080/actuator/health
- API docs: http://localhost:8080/swagger-ui/index.html (click **Authorize**, log in as a test user below)
- Keycloak admin console: http://localhost:8180 (admin / admin)

Test users (password `password` for all; local realm only, defined in `infra/compose/keycloak/dispatch-realm.json`):

| Username | Role | Tenant |
|---|---|---|
| `dispatcher.meghna` | DISPATCHER | Meghna Bank |
| `admin.meghna` | CLIENT_ADMIN | Meghna Bank |
| `dispatcher.shwapno` | DISPATCHER | Shwapno Retail |
| `tech.rahim` | TECHNICIAN | none (marketplace-wide) |
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

## Decisions

See [docs/adr](docs/adr).
