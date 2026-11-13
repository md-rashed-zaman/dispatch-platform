-- Tenant isolation inside the database (ADR 0003). The application layer (Hibernate @TenantId) already
-- filters by tenant; these policies hold even when that layer is skipped (native SQL, a missing annotation).

-- The app connects as dispatch_app: not a superuser, no BYPASSRLS, owns nothing, so every policy below
-- applies to it. Flyway keeps using the owner role, which must see all rows for data migrations.
DO $$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'dispatch_app') THEN
    CREATE ROLE dispatch_app LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE PASSWORD '${app-password}';
  END IF;
END
$$;

GRANT USAGE ON SCHEMA public TO dispatch_app;
GRANT SELECT, INSERT, UPDATE, DELETE
  ON company, technician, work_order, checklist_item, offer, event_publication TO dispatch_app;
-- Tables created by later migrations get the same rights.
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO dispatch_app;

-- The current tenant, set per transaction by the app with set_config('app.tenant_id', <uuid>, true).
-- Unset (or reset to '' after a previous transaction on the same connection) means NULL, which equals
-- nothing: no tenant sees no rows and can't write any.
CREATE FUNCTION current_tenant_id() RETURNS UUID
  LANGUAGE sql STABLE
  AS $$ SELECT NULLIF(current_setting('app.tenant_id', true), '')::uuid $$;

ALTER TABLE work_order ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON work_order
  USING (tenant_id = current_tenant_id())
  WITH CHECK (tenant_id = current_tenant_id());

-- Children have no tenant_id of their own: a row is visible when its work order is. The subquery is
-- itself filtered by work_order's policy above.
ALTER TABLE checklist_item ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON checklist_item
  USING (EXISTS (SELECT FROM work_order w WHERE w.id = work_order_id))
  WITH CHECK (EXISTS (SELECT FROM work_order w WHERE w.id = work_order_id));

-- shortcut: tenant users only. Technicians see their own offers once offers are built (step 1.6), which
-- adds an "OR technician_id = <current technician>" clause here.
ALTER TABLE offer ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON offer
  USING (EXISTS (SELECT FROM work_order w WHERE w.id = work_order_id))
  WITH CHECK (EXISTS (SELECT FROM work_order w WHERE w.id = work_order_id));
