# 3. Shared schema with row-level security for tenancy

Date: 2026-11-14
Status: Accepted

## Context

Dispatch serves many client companies (tenants) from one application and one database. A bank must never see a garment factory's work orders: addresses, contacts, what is broken where. In B2B software a single cross-tenant leak can end a contract.

The usual way leaks happen is not an attacker. It's a developer who writes one query, report or export and forgets `WHERE tenant_id = ?`. So isolation can't depend on every developer remembering a filter every time.

Not all data is tenant data. Work orders, their checklists and offers belong to one company. Technicians are freelancers who work for many companies, and companies themselves are the tenant list.

## Decision

1. **One shared schema.** Tenant-owned tables have a `tenant_id` column (or reach one through their work order).
2. **The tenant comes only from the verified access token** (`tenant_id` claim), never from a URL, header or request body. Jobs and event consumers name the tenant explicitly (`TenantContext.callAs`).
3. **Two independent layers enforce it:**
   - **Hibernate `@TenantId`** adds `tenant_id = <current tenant>` to every query on tenant entities, also to loads by id, and refuses to save an entity for a different tenant.
   - **Postgres row-level security** (migration V4) applies the same rule inside the database. Every transaction starts with `set_config('app.tenant_id', <tenant>, true)`. The `true` makes it transaction-local, so it disappears at commit and can't leak to the next request that borrows the same pooled connection.
4. **The app connects as `dispatch_app`.** This role is not a superuser, has no `BYPASSRLS` and owns no tables, because RLS silently doesn't apply to any of those. Flyway migrates as the owner. The app refuses to start if its database user would bypass RLS.
5. **Fail closed.** No tenant (a technician or platform admin calling a tenant endpoint, a job that forgot to set one) means zero rows and no writes in both layers, never "all rows".
6. **Another tenant's id returns 404, not 403.** A 403 would confirm that the id exists.

## Alternatives considered

- **Database per tenant.** Strongest isolation and no noisy neighbors, but N databases to migrate, back up and monitor, and each costs money while idle. Worth it for a few large regulated customers, not for many small companies. Still possible later for one big tenant, because the code already treats the tenant as context.
- **Schema per tenant.** Good isolation, but every migration runs once per schema, and Postgres struggles with thousands of schemas.
- **Shared schema, application filter only.** Simplest, but one forgotten filter or native query leaks. Rejected as the only layer.
- **`FORCE ROW LEVEL SECURITY`** (apply policies to the table owner too). Not used: the owner is the migration role, and data migrations must see every tenant's rows. With FORCE, a data migration would silently see zero rows and change nothing. The app never connects as the owner, and the startup check enforces that.
- **Session-level `SET app.tenant_id`** (or a Hibernate `MultiTenantConnectionProvider` that sets it per connection). Rejected: the value stays on the pooled connection, and a missed reset hands the next request another tenant's identity.

## Consequences

Good:
- A forgotten filter, a native SQL report or a missing annotation still can't cross tenants. Tests prove each layer on its own (`TenantIsolationTests`): with `@TenantId` removed, every test still passes on RLS alone. With RLS off, the API is still protected by Hibernate, and only the raw-SQL tests fail.
- Developers write ordinary queries. Isolation is not something each feature has to remember.

Bad / accepted costs:
- RLS hides rows silently. A query that "returns nothing" may just be missing its tenant, which can confuse debugging. The startup check and fail-closed defaults make this the safe kind of confusing.
- The tenant must be known when the transaction starts. Tests can't wrap everything in one `@Transactional` that starts before they set the tenant.
- Child tables (`checklist_item`, `offer`) check their work order in a subquery. That's one primary-key lookup per row, fine at this scale.
- Platform-wide operations (support tools, cross-tenant reports) will need a separate audited path with its own role. They don't exist yet.
- Technicians aren't tenant members. Their access to offers and work orders needs an extra policy clause ("offered to me"), which is an ownership rule, not a tenant rule. Added in V7 (step 1.6, ADR 0005).
- Caches, unique constraints and indexes must include `tenant_id` from now on (see T09).
