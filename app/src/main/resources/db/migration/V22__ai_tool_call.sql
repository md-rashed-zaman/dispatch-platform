-- Every tool call made through the MCP server (step 4.5, ADR 0019, T36): who called which tool with what, and how
-- it ended. A write tool's first call is a preview with a one-time confirmation; the second call carries it.

CREATE TABLE ai_tool_call
(
  id                 UUID PRIMARY KEY,
  -- The caller's company; null for callers who have none (technicians, platform admins).
  tenant_id          UUID REFERENCES company (id),
  user_id            UUID        NOT NULL,
  tool               TEXT        NOT NULL,
  arguments          JSONB       NOT NULL,
  outcome            TEXT        NOT NULL CHECK (outcome IN ('OK', 'NEEDS_CONFIRMATION', 'CONFIRMED', 'DENIED',
                                                             'NOT_FOUND', 'REJECTED', 'ERROR')),
  detail             TEXT,
  -- Only for NEEDS_CONFIRMATION: the hash of the one-time token (never the token itself), what it confirms
  -- (a hash of the tool and its arguments), until when, and when it was used.
  confirmation_hash  TEXT UNIQUE,
  confirms_hash      TEXT,
  expires_at         TIMESTAMPTZ,
  confirmed_at       TIMESTAMPTZ,
  created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ai_tool_call_user_idx ON ai_tool_call (user_id, created_at DESC);

-- A company's staff see their company's calls; a caller without a company writes rows without one, which no
-- company can read.
ALTER TABLE ai_tool_call ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON ai_tool_call
  USING (tenant_id = current_tenant_id())
  WITH CHECK (tenant_id IS NOT DISTINCT FROM current_tenant_id());
