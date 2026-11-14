package dev.rashed.dispatch.workorder;

/** How far the SLA policy has gone for a work order waiting for a technician (step 2.5). */
public enum SlaStage {
    ON_TRACK,
    /** Offered to technicians farther away. */
    WIDENED,
    /** The company was told nobody has taken it. */
    ESCALATED
}
