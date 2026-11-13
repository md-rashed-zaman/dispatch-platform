-- Why submitted work was sent back to the technician (SUBMITTED -> IN_PROGRESS). Cleared on resubmit.
ALTER TABLE work_order
  ADD COLUMN rejection_reason TEXT CHECK (length(rejection_reason) BETWEEN 1 AND 1000);
