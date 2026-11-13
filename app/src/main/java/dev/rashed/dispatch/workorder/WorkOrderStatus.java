package dev.rashed.dispatch.workorder;

/** Lifecycle states. Which moves are allowed between them is {@link Transition}'s table. */
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
