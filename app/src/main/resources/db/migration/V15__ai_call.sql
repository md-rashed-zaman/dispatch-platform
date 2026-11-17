-- Every call the AI gateway makes, or decides not to make (step 3.2, ADR 0012, T28).
-- One row per attempt: a feature that retried once and then fell back to a second model has three rows, so
-- the audit shows exactly what happened. Input is stored redacted (no emails or phone numbers); the full prompt
-- can be rebuilt from the feature's prompt version and this input.
CREATE TABLE ai_call
(
  id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tenant_id      UUID        NOT NULL REFERENCES company (id),
  feature        TEXT        NOT NULL CHECK (feature ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
  prompt_version TEXT        NOT NULL,
  model          TEXT        NOT NULL,
  -- CALLED outcomes reached the provider (OK, ERROR, INVALID_OUTPUT) and count against the daily budget;
  -- the others were decided locally without a network call.
  outcome        TEXT        NOT NULL CHECK (outcome IN ('OK', 'ERROR', 'INVALID_OUTPUT', 'REPLAYED',
                                                         'RATE_LIMITED', 'CIRCUIT_OPEN', 'BUDGET_EXCEEDED')),
  input_redacted TEXT        NOT NULL,
  output         TEXT,
  error          TEXT,
  input_tokens   INT CHECK (input_tokens >= 0),
  output_tokens  INT CHECK (output_tokens >= 0),
  latency_ms     INT         NOT NULL CHECK (latency_ms >= 0),
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The daily budget check: this tenant's calls since midnight.
CREATE INDEX ai_call_tenant_created_idx ON ai_call (tenant_id, created_at);

-- Tenant-owned like work orders: a company sees only its own AI calls.
ALTER TABLE ai_call ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON ai_call
  USING (tenant_id = current_tenant_id())
  WITH CHECK (tenant_id = current_tenant_id());
