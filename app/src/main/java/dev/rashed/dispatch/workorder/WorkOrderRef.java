package dev.rashed.dispatch.workorder;

import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Just enough about a work order for another module to decide what the caller may do with it. */
public record WorkOrderRef(
        UUID id,
        UUID tenantId,
        WorkOrderStatus status,
        @Nullable UUID technicianId) {

    private static final Set<WorkOrderStatus> CLOSED =
            Set.of(WorkOrderStatus.APPROVED, WorkOrderStatus.PAID, WorkOrderStatus.CANCELLED);

    /** Approved, paid or cancelled: nothing about the job changes any more. */
    public boolean isClosed() {
        return CLOSED.contains(status);
    }
}
