-- Payments (step 4.2, ADR 0015, docs/diagrams/payment-flow.md): the client funds a work order's budget through a
-- hosted checkout once a technician is assigned; a payment is captured only by Dispatch's own call to the provider.

-- Set when the work order's payment is captured; starting work requires it (dispatch.payments.required).
ALTER TABLE work_order ADD COLUMN funded_at TIMESTAMPTZ;

CREATE TABLE payment
(
  id             UUID PRIMARY KEY,
  tenant_id      UUID        NOT NULL REFERENCES company (id),
  work_order_id  UUID        NOT NULL REFERENCES work_order (id),
  -- Our id at the provider (SSLCommerz tran_id, at most 30 characters): the payment id in base 36.
  tran_id        TEXT        NOT NULL UNIQUE CHECK (length(tran_id) <= 30),
  status         TEXT        NOT NULL CHECK (status IN ('INITIATED', 'PENDING', 'CAPTURED', 'FAILED', 'EXPIRED')),
  amount_minor   BIGINT      NOT NULL CHECK (amount_minor > 0),
  currency       TEXT        NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
  session_key    TEXT,
  gateway_url    TEXT,
  -- What the provider said when it settled: its validation id (unique: one capture per provider transaction),
  -- the bank's id (needed for refunds), its own status word and risk flag.
  val_id         TEXT UNIQUE,
  bank_tran_id   TEXT,
  provider_status TEXT,
  risk_level     SMALLINT,
  -- Why a payment is FAILED or EXPIRED, or why a PENDING one waits ("risk review").
  reason         TEXT,
  created_by     UUID,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  settled_at     TIMESTAMPTZ
);

-- One open or captured payment per work order: a new attempt only after the last one failed or expired. A
-- double capture is impossible even if the code has a bug.
CREATE UNIQUE INDEX payment_one_live_per_work_order ON payment (work_order_id)
  WHERE status IN ('INITIATED', 'PENDING', 'CAPTURED');
-- The reconciler: open payments by age.
CREATE INDEX payment_open_idx ON payment (tenant_id, created_at) WHERE status IN ('INITIATED', 'PENDING');
CREATE INDEX payment_updated_idx ON payment (tenant_id, updated_at);

ALTER TABLE payment ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON payment
  USING (tenant_id = current_tenant_id())
  WITH CHECK (tenant_id = current_tenant_id());

-- The provider's notification (IPN) arrives with no signed-in user and no tenant: it names only our tran_id.
-- This finds whose payment it is, and nothing else; everything after runs as that tenant under row-level security.
CREATE FUNCTION payment_tenant(p_tran_id TEXT) RETURNS UUID
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public
  AS $$ SELECT tenant_id FROM payment WHERE tran_id = p_tran_id $$;
REVOKE ALL ON FUNCTION payment_tenant(TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION payment_tenant(TEXT) TO dispatch_app;

-- Idempotency keys (ADR 0016): the first request with a key stores a hash of itself and, when done, its response.
-- A retry with the same key and body gets the stored response; the same key with another body is an error.
CREATE TABLE idempotency_key
(
  tenant_id       UUID        NOT NULL REFERENCES company (id),
  idem_key        TEXT        NOT NULL CHECK (length(idem_key) BETWEEN 1 AND 255),
  scope           TEXT        NOT NULL,
  request_hash    TEXT        NOT NULL,
  status          TEXT        NOT NULL CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),
  response_status INT,
  response_body   JSONB,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  completed_at    TIMESTAMPTZ,
  PRIMARY KEY (tenant_id, idem_key)
);

ALTER TABLE idempotency_key ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON idempotency_key
  USING (tenant_id = current_tenant_id())
  WITH CHECK (tenant_id = current_tenant_id());

-- The daily reconciliation's findings: where our state and the provider's disagree. A person looks at each.
CREATE TABLE payment_mismatch
(
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tenant_id       UUID        NOT NULL REFERENCES company (id),
  payment_id      UUID        NOT NULL REFERENCES payment (id),
  local_status    TEXT        NOT NULL,
  provider_status TEXT        NOT NULL,
  detail          TEXT        NOT NULL,
  found_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (payment_id, local_status, provider_status)
);

ALTER TABLE payment_mismatch ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON payment_mismatch
  USING (tenant_id = current_tenant_id())
  WITH CHECK (tenant_id = current_tenant_id());
