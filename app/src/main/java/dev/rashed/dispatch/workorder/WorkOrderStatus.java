package dev.rashed.dispatch.workorder;

/** Lifecycle states. Transitions are enforced by {@link WorkOrder} methods (step 1.4). */
public enum WorkOrderStatus {
    DRAFT,
    PUBLISHED,
    ROUTED,
    ASSIGNED,
    IN_PROGRESS,
    SUBMITTED,
    APPROVED,
    PAID,
    CANCELLED
}
