package dev.rashed.dispatch.workorder;

import java.time.Instant;
import java.util.UUID;

/**
 * A work order moved through its lifecycle. Spring Data publishes it when the work order is saved; listeners
 * in other modules (from Phase 2: matching on PUBLISHED, later payment on APPROVED) use after-commit
 * listeners, so they never act on a change that was rolled back.
 */
public record WorkOrderStatusChanged(
        UUID workOrderId,
        UUID tenantId,
        WorkOrderStatus from,
        WorkOrderStatus to,
        UUID technicianId,
        Instant occurredAt) {}
