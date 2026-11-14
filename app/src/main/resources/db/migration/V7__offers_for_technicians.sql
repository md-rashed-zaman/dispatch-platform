-- Step 1.6: technicians see and accept their offers. Technicians belong to no tenant, so row-level security
-- needs a second identity: the current technician, set per transaction like the tenant (ADR 0003, 0005).

-- Each offer records its tenant itself. The offer policies then only look at the offer's own columns, so
-- the work_order policy below can look at offers without the two policies referring to each other (which
-- Postgres refuses as infinite recursion). The composite foreign key keeps the copy honest: an offer's
-- tenant is always its work order's tenant.
ALTER TABLE work_order ADD CONSTRAINT work_order_id_tenant_unique UNIQUE (id, tenant_id);
ALTER TABLE offer ADD COLUMN tenant_id UUID;
UPDATE offer o SET tenant_id = w.tenant_id FROM work_order w WHERE w.id = o.work_order_id;
ALTER TABLE offer
  ALTER COLUMN tenant_id SET NOT NULL,
  ADD CONSTRAINT offer_work_order_same_tenant
    FOREIGN KEY (work_order_id, tenant_id) REFERENCES work_order (id, tenant_id);

-- The current technician, set per transaction with set_config('app.technician_id', <uuid>, true) from the
-- token's technician_id claim. NULL for everyone else, which matches nothing.
CREATE FUNCTION current_technician_id() RETURNS UUID
  LANGUAGE sql STABLE
  AS $$ SELECT NULLIF(current_setting('app.technician_id', true), '')::uuid $$;

DROP POLICY tenant_isolation ON offer;
CREATE POLICY tenant_isolation ON offer
  USING (tenant_id = current_tenant_id())
  WITH CHECK (tenant_id = current_tenant_id());
-- Read only: accepting runs as the work order's tenant, after the app has checked the offer is the caller's.
CREATE POLICY technician_reads_own_offers ON offer FOR SELECT
  USING (technician_id = current_technician_id());

-- A technician may read the work orders offered or assigned to them, nothing else. The IS NOT NULL test is
-- evaluated once per query, so company users never pay for the subquery.
CREATE POLICY technician_reads_offered_work ON work_order FOR SELECT
  USING (current_technician_id() IS NOT NULL
         AND (technician_id = current_technician_id()
              OR EXISTS (SELECT FROM offer o
                         WHERE o.work_order_id = work_order.id AND o.technician_id = current_technician_id())));

-- "My open offers", newest first, one page at a time (keyset, T13).
DROP INDEX offer_technician_status_idx;
CREATE INDEX offer_technician_status_created_idx ON offer (technician_id, status, created_at DESC, id DESC);
