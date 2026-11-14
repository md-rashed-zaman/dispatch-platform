-- Files attached to work orders (step 1.7, T15). The bytes live in object storage; this table is the
-- metadata and the only link between a work order and its files.
CREATE TABLE attachment
(
  id            UUID PRIMARY KEY,
  tenant_id     UUID        NOT NULL,
  work_order_id UUID        NOT NULL,
  object_key    TEXT        NOT NULL UNIQUE,
  file_name     TEXT        NOT NULL CHECK (length(file_name) BETWEEN 1 AND 255),
  content_type  TEXT        NOT NULL CHECK (content_type IN ('image/jpeg', 'image/png', 'application/pdf')),
  declared_size BIGINT      NOT NULL CHECK (declared_size BETWEEN 1 AND 10485760),  -- 10 MB
  size          BIGINT CHECK (size BETWEEN 1 AND 10485760),
  status        TEXT        NOT NULL CHECK (status IN ('PENDING', 'CONFIRMED')),
  uploaded_by   UUID        NOT NULL,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  confirmed_at  TIMESTAMPTZ,
  version       BIGINT      NOT NULL DEFAULT 0,
  -- The attachment's tenant is always its work order's tenant.
  CONSTRAINT attachment_work_order_same_tenant
    FOREIGN KEY (work_order_id, tenant_id) REFERENCES work_order (id, tenant_id) ON DELETE CASCADE,
  -- Every key starts with its own tenant's prefix, so no row can point into another tenant's files.
  CONSTRAINT attachment_key_in_tenant_prefix CHECK (object_key LIKE 'tenants/' || tenant_id || '/%'),
  CONSTRAINT attachment_confirmed_has_size
    CHECK (status = 'PENDING' OR (size IS NOT NULL AND confirmed_at IS NOT NULL))
);

-- A work order's files, oldest first.
CREATE INDEX attachment_work_order_idx ON attachment (work_order_id, created_at);
-- The cleanup job looks only for old pending uploads.
CREATE INDEX attachment_pending_created_idx ON attachment (created_at) WHERE status = 'PENDING';

-- Technicians reach attachments as the work order's tenant, after the app checked they're assigned
-- (like accepting an offer, ADR 0005), so the tenant policy is the only one needed.
ALTER TABLE attachment ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON attachment
  USING (tenant_id = current_tenant_id())
  WITH CHECK (tenant_id = current_tenant_id());
