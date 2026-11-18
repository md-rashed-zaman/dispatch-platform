-- Completion audit with a human in the loop (step 3.5, T31).

-- What the technician says when submitting: "Replaced the drum, test page attached." A claim, not evidence:
-- the audit checks it against the photos.
ALTER TABLE work_order ADD COLUMN completion_notes TEXT CHECK (length(completion_notes) <= 2000);

-- One audit per submission. The AI recommends; code decides whether that's enough to approve on its own;
-- otherwise a person decides. Every row keeps what the AI said and what was finally decided, so cases where
-- people overrode the AI become eval data.
CREATE TABLE completion_audit
(
  id               UUID PRIMARY KEY,
  tenant_id        UUID        NOT NULL REFERENCES company (id),
  work_order_id    UUID        NOT NULL REFERENCES work_order (id) ON DELETE CASCADE,
  source_event_id  UUID        NOT NULL UNIQUE,  -- the SUBMITTED event: a redelivery audits nothing twice
  -- NEEDS_REVIEW until a person or the policy decides; DECIDED_ELSEWHERE when the work order was approved,
  -- rejected or cancelled outside the review queue.
  state            TEXT        NOT NULL CHECK (state IN ('NEEDS_REVIEW', 'AUTO_APPROVED', 'APPROVED', 'REJECTED',
                                                        'DECIDED_ELSEWHERE')),
  model            TEXT,                         -- null when no model answered
  prompt_version   TEXT        NOT NULL,
  items            JSONB       NOT NULL DEFAULT '[]', -- per checklist item: position, verdict, evidence
  confidence       NUMERIC(4, 3) CHECK (confidence BETWEEN 0 AND 1),
  photo_count      INT         NOT NULL CHECK (photo_count >= 0),
  recommendation   TEXT        NOT NULL CHECK (recommendation IN ('APPROVE', 'REVIEW')),
  reason           TEXT        NOT NULL,         -- why the policy approved or sent it to a person
  decided_by       TEXT,                         -- reviewer's user id, or 'system' for an auto-approval
  decision_reason  TEXT,                         -- required for a rejection
  decided_at       TIMESTAMPTZ,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  CHECK ((state = 'NEEDS_REVIEW') = (decided_at IS NULL))
);

-- The review queue: a company's open audits, oldest first.
CREATE INDEX completion_audit_queue_idx ON completion_audit (tenant_id, created_at) WHERE state = 'NEEDS_REVIEW';
CREATE INDEX completion_audit_work_order_idx ON completion_audit (work_order_id, created_at);

ALTER TABLE completion_audit ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON completion_audit
  USING (tenant_id = current_tenant_id())
  WITH CHECK (tenant_id = current_tenant_id());
