-- Feature flags and a small experiment (step 4.4, ADR 0018, T35). Flags are the platform's, not a company's: no
-- row-level security; only platform admins change them, and every change is kept.

CREATE TABLE feature_flag
(
  key             TEXT PRIMARY KEY CHECK (key ~ '^[a-z0-9]+([.-][a-z0-9]+)*$'),
  description     TEXT        NOT NULL,
  -- The kill switch: off means off for everyone, whatever the rules say.
  enabled         BOOLEAN     NOT NULL,
  -- On for this share of companies, picked by a stable hash of the flag and the company (sticky).
  rollout_percent INT         NOT NULL CHECK (rollout_percent BETWEEN 0 AND 100),
  -- Always on, or always off, for these companies, before the percentage.
  allow_tenants   UUID[]      NOT NULL DEFAULT '{}',
  deny_tenants    UUID[]      NOT NULL DEFAULT '{}',
  version         BIGINT      NOT NULL DEFAULT 0,
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by      UUID
);

-- Who changed what, when and why: the before and after of every change.
CREATE TABLE feature_flag_change
(
  id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  flag_key   TEXT        NOT NULL REFERENCES feature_flag (key),
  changed_by UUID,
  reason     TEXT        NOT NULL,
  before     JSONB,
  after      JSONB       NOT NULL,
  changed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX feature_flag_change_flag_idx ON feature_flag_change (flag_key, changed_at DESC);

-- The first experiment: AI auto-approval for 20% of companies (the others get the AI's recommendation, a person
-- decides). dispatch.review.auto-approve stays as the global kill switch above it.
INSERT INTO feature_flag (key, description, enabled, rollout_percent)
VALUES ('ai.auto-approve',
        'Approve finished work without a person when the AI check is sure (step 3.5); off sends it to the review queue',
        true, 20);
INSERT INTO feature_flag_change (flag_key, reason, after)
SELECT key, 'Created by migration V21: 20% rollout',
       jsonb_build_object('enabled', enabled, 'rolloutPercent', rollout_percent)
FROM feature_flag;

-- Which arm of the experiment each audited job was in, decided once when it was audited: the analysis compares
-- the arms by what really happened to each job, not by a flag's value today.
ALTER TABLE completion_audit ADD COLUMN auto_approve_variant TEXT CHECK (auto_approve_variant IN ('on', 'off'));
-- Whether the AI's assessment would have approved on its own (every item MET, sure enough), whatever the arm: in
-- the "off" arm a person then decides, and how often they disagree is the experiment's guardrail.
ALTER TABLE completion_audit ADD COLUMN ai_would_approve BOOLEAN;
CREATE INDEX completion_audit_experiment_idx ON completion_audit (auto_approve_variant, created_at);

-- The experiment's report, across every company (a platform admin reads it): counts and times per arm only,
-- nothing about any one company or job leaves (SECURITY DEFINER, like ledger_totals()).
CREATE FUNCTION auto_approve_experiment(since TIMESTAMPTZ)
  RETURNS TABLE (variant TEXT, audits BIGINT, decided BIGINT, approved BIGINT, rejected BIGINT,
                 auto_approved BIGINT, minutes_to_approval_p50 NUMERIC, minutes_to_approval_p90 NUMERIC,
                 ai_approvable_decided_by_person BIGINT, ai_approvable_rejected_by_person BIGINT)
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public
AS $$
  SELECT auto_approve_variant,
         count(*),
         count(*) FILTER (WHERE state <> 'NEEDS_REVIEW'),
         count(*) FILTER (WHERE state IN ('APPROVED', 'AUTO_APPROVED')
                             OR (state = 'DECIDED_ELSEWHERE' AND decision_reason = 'APPROVED')),
         count(*) FILTER (WHERE state = 'REJECTED'
                             OR (state = 'DECIDED_ELSEWHERE' AND decision_reason = 'IN_PROGRESS')),
         count(*) FILTER (WHERE state = 'AUTO_APPROVED'),
         round((percentile_cont(0.5) WITHIN GROUP (ORDER BY extract(epoch FROM decided_at - created_at) / 60)
                FILTER (WHERE state IN ('APPROVED', 'AUTO_APPROVED')
                           OR (state = 'DECIDED_ELSEWHERE' AND decision_reason = 'APPROVED')))::numeric, 1),
         round((percentile_cont(0.9) WITHIN GROUP (ORDER BY extract(epoch FROM decided_at - created_at) / 60)
                FILTER (WHERE state IN ('APPROVED', 'AUTO_APPROVED')
                           OR (state = 'DECIDED_ELSEWHERE' AND decision_reason = 'APPROVED')))::numeric, 1),
         count(*) FILTER (WHERE ai_would_approve AND state IN ('APPROVED', 'REJECTED', 'DECIDED_ELSEWHERE')),
         count(*) FILTER (WHERE ai_would_approve AND (state = 'REJECTED'
                             OR (state = 'DECIDED_ELSEWHERE' AND decision_reason = 'IN_PROGRESS')))
  FROM completion_audit
  WHERE auto_approve_variant IS NOT NULL AND created_at >= since
  GROUP BY auto_approve_variant
$$;
REVOKE ALL ON FUNCTION auto_approve_experiment(TIMESTAMPTZ) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION auto_approve_experiment(TIMESTAMPTZ) TO dispatch_app;
