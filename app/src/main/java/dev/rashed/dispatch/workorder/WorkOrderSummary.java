package dev.rashed.dispatch.workorder;

import dev.rashed.dispatch.shared.Money;
import dev.rashed.dispatch.shared.TimeWindow;
import java.time.Instant;
import java.util.UUID;

/** One row of a work order list: no checklist, so listing never loads checklist items. */
public record WorkOrderSummary(
        UUID id,
        String title,
        WorkOrderStatus status,
        String siteAddress,
        TimeWindow timeWindow,
        Money budget,
        Instant createdAt) {

    static WorkOrderSummary of(WorkOrder workOrder) {
        return new WorkOrderSummary(
                workOrder.id(),
                workOrder.title(),
                workOrder.status(),
                workOrder.siteAddress(),
                workOrder.timeWindow(),
                workOrder.budget(),
                workOrder.createdAt());
    }
}
