-- SLA policy (step 2.5, T22): how long a work order has waited for a technician, and what was done about it.
ALTER TABLE work_order ADD COLUMN published_at TIMESTAMPTZ;
ALTER TABLE work_order ADD COLUMN sla_stage TEXT NOT NULL DEFAULT 'ON_TRACK'
  CHECK (sla_stage IN ('ON_TRACK', 'WIDENED', 'ESCALATED'));

-- Work orders already published keep their place in line.
UPDATE work_order SET published_at = created_at WHERE status <> 'DRAFT' AND published_at IS NULL;

-- The SLA job only looks at work orders still waiting for a technician, oldest first.
CREATE INDEX work_order_waiting_idx ON work_order (tenant_id, published_at)
  WHERE status IN ('PUBLISHED', 'ROUTED');

-- ShedLock: one row per scheduled job. The instance that holds a job's row until lock_until runs it; the
-- others skip that round. Times come from the database clock (usingDbTime), so instances' clocks don't matter.
CREATE TABLE shedlock
(
  name       VARCHAR(64)  NOT NULL PRIMARY KEY,
  lock_until TIMESTAMPTZ  NOT NULL,
  locked_at  TIMESTAMPTZ  NOT NULL,
  locked_by  VARCHAR(255) NOT NULL
);
