-- A small double-entry ledger (step 4.3, ADR 0017, docs/diagrams/payment-flow.md "Ledger postings"). Every money
-- movement is one journal entry whose lines' debits equal their credits; entries are never changed, only
-- reversed. Amounts are integers in minor units (poisha).

CREATE TABLE ledger_account
(
  code TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  -- Assets and expenses grow with debits; liabilities and revenue with credits.
  kind TEXT NOT NULL CHECK (kind IN ('ASSET', 'LIABILITY', 'REVENUE', 'EXPENSE'))
);

INSERT INTO ledger_account (code, name, kind) VALUES
  ('bank', 'Platform bank account', 'ASSET'),
  ('gateway_clearing', 'Owed to us by the payment gateway', 'ASSET'),
  ('client_escrow', 'Clients'' money held until approval', 'LIABILITY'),
  ('technician_payable', 'Owed to technicians', 'LIABILITY'),
  ('platform_revenue', 'Platform fees earned', 'REVENUE'),
  ('gateway_fees', 'Payment gateway charges', 'EXPENSE');

CREATE TABLE journal_entry
(
  id                UUID PRIMARY KEY,
  tenant_id         UUID        NOT NULL REFERENCES company (id),
  -- What caused it, unique per company: posting the same thing twice (a retried listener) is a no-op.
  reference         TEXT        NOT NULL,
  kind              TEXT        NOT NULL CHECK (kind IN ('CAPTURE', 'GATEWAY_FEE', 'APPROVAL', 'PAYOUT', 'REFUND', 'REVERSAL')),
  work_order_id     UUID,
  currency          TEXT        NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
  description       TEXT        NOT NULL,
  reverses_entry_id UUID REFERENCES journal_entry (id),
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, reference)
);
CREATE INDEX journal_entry_work_order_idx ON journal_entry (work_order_id);
-- An entry is reversed at most once.
CREATE UNIQUE INDEX journal_entry_one_reversal ON journal_entry (reverses_entry_id) WHERE reverses_entry_id IS NOT NULL;

CREATE TABLE journal_line
(
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  entry_id      UUID   NOT NULL REFERENCES journal_entry (id),
  tenant_id     UUID   NOT NULL REFERENCES company (id),
  account_code  TEXT   NOT NULL REFERENCES ledger_account (code),
  -- Whose money, where the account is shared: the technician for technician_payable.
  technician_id UUID REFERENCES technician (id),
  debit_minor   BIGINT NOT NULL DEFAULT 0 CHECK (debit_minor >= 0),
  credit_minor  BIGINT NOT NULL DEFAULT 0 CHECK (credit_minor >= 0),
  -- Exactly one side per line.
  CHECK ((debit_minor > 0) <> (credit_minor > 0))
);
CREATE INDEX journal_line_entry_idx ON journal_line (entry_id);
CREATE INDEX journal_line_account_idx ON journal_line (tenant_id, account_code);

-- Balanced, checked by the database at commit (deferred: the lines of an entry are inserted one by one). The
-- application checks first; this holds even for code that forgets.
CREATE FUNCTION journal_entry_balanced() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  entry UUID;
  lines INT;
  difference BIGINT;
BEGIN
  -- IF, not CASE: each branch is planned only when it runs, and journal_entry rows have no entry_id field.
  IF TG_TABLE_NAME = 'journal_entry' THEN
    entry := NEW.id;
  ELSE
    entry := NEW.entry_id;
  END IF;
  SELECT count(*), coalesce(sum(debit_minor), 0) - coalesce(sum(credit_minor), 0)
    INTO lines, difference FROM journal_line WHERE entry_id = entry;
  IF lines < 2 OR difference <> 0 THEN
    RAISE EXCEPTION 'journal entry % is unbalanced: % lines, debits minus credits %', entry, lines, difference
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NULL;
END $$;

CREATE CONSTRAINT TRIGGER journal_entry_balanced AFTER INSERT ON journal_entry
  DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION journal_entry_balanced();
CREATE CONSTRAINT TRIGGER journal_line_balanced AFTER INSERT ON journal_line
  DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION journal_entry_balanced();

-- Append-only: corrections are reversing entries, never edits. The app role may not update or delete, and a
-- trigger stops everyone else too.
REVOKE UPDATE, DELETE, TRUNCATE ON journal_entry, journal_line FROM dispatch_app;
CREATE FUNCTION journal_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'the ledger is append-only: post a reversing entry instead' USING ERRCODE = 'insufficient_privilege';
END $$;
CREATE TRIGGER journal_entry_append_only BEFORE UPDATE OR DELETE ON journal_entry
  FOR EACH ROW EXECUTE FUNCTION journal_append_only();
CREATE TRIGGER journal_line_append_only BEFORE UPDATE OR DELETE ON journal_line
  FOR EACH ROW EXECUTE FUNCTION journal_append_only();

ALTER TABLE journal_entry ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON journal_entry
  USING (tenant_id = current_tenant_id()) WITH CHECK (tenant_id = current_tenant_id());
ALTER TABLE journal_line ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON journal_line
  USING (tenant_id = current_tenant_id()) WITH CHECK (tenant_id = current_tenant_id());

-- The whole platform's totals per account, for the platform admin and the nightly check. Row-level security
-- rightly hides other companies' lines, so this one aggregate runs as the owner: sums only, no rows leave.
CREATE FUNCTION ledger_totals()
  RETURNS TABLE (account_code TEXT, debits BIGINT, credits BIGINT)
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public
AS $$
  SELECT a.code, coalesce(sum(l.debit_minor), 0)::bigint, coalesce(sum(l.credit_minor), 0)::bigint
  FROM ledger_account a LEFT JOIN journal_line l ON l.account_code = a.code
  GROUP BY a.code
$$;
REVOKE ALL ON FUNCTION ledger_totals() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ledger_totals() TO dispatch_app;

-- What the gateway keeps of each payment: SSLCommerz pays out store_amount (amount minus its charge).
ALTER TABLE payment ADD COLUMN store_amount_minor BIGINT CHECK (store_amount_minor >= 0);

-- Refunds (cancelled before work started, or paid for a job that was cancelled meanwhile): a second movement
-- of money, never a change to the captured payment.
CREATE TABLE refund
(
  id             UUID PRIMARY KEY,
  tenant_id      UUID        NOT NULL REFERENCES company (id),
  payment_id     UUID        NOT NULL UNIQUE REFERENCES payment (id),
  amount_minor   BIGINT      NOT NULL CHECK (amount_minor > 0),
  currency       TEXT        NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
  -- Our id for the refund at the provider (SSLCommerz refund_trans_id, at most 30 characters).
  refund_trans_id TEXT       NOT NULL UNIQUE CHECK (length(refund_trans_id) <= 30),
  refund_ref_id  TEXT,
  status         TEXT        NOT NULL CHECK (status IN ('REQUESTED', 'PROCESSING', 'REFUNDED', 'FAILED')),
  reason         TEXT        NOT NULL,
  provider_note  TEXT,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  settled_at     TIMESTAMPTZ
);
CREATE INDEX refund_open_idx ON refund (tenant_id, created_at) WHERE status IN ('REQUESTED', 'PROCESSING');
ALTER TABLE refund ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON refund
  USING (tenant_id = current_tenant_id()) WITH CHECK (tenant_id = current_tenant_id());

-- What a technician is owed for an approved job, until the platform has paid it out.
CREATE TABLE payout
(
  id            UUID PRIMARY KEY,
  tenant_id     UUID        NOT NULL REFERENCES company (id),
  work_order_id UUID        NOT NULL UNIQUE REFERENCES work_order (id),
  technician_id UUID        NOT NULL REFERENCES technician (id),
  amount_minor  BIGINT      NOT NULL CHECK (amount_minor > 0),
  currency      TEXT        NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
  status        TEXT        NOT NULL CHECK (status IN ('PENDING', 'PAID')),
  bank_reference TEXT,
  paid_by       UUID,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  paid_at       TIMESTAMPTZ
);
CREATE INDEX payout_pending_idx ON payout (tenant_id, created_at) WHERE status = 'PENDING';
ALTER TABLE payout ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON payout
  USING (tenant_id = current_tenant_id()) WITH CHECK (tenant_id = current_tenant_id());

-- A platform admin confirms a payout by id with no company of their own: this names its company, nothing else.
CREATE FUNCTION payout_tenant(p_id UUID) RETURNS UUID
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public
  AS $$ SELECT tenant_id FROM payout WHERE id = p_id $$;
REVOKE ALL ON FUNCTION payout_tenant(UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION payout_tenant(UUID) TO dispatch_app;
