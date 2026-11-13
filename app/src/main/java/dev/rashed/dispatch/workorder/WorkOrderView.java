package dev.rashed.dispatch.workorder;

import dev.rashed.dispatch.shared.Money;
import java.time.Instant;
import java.util.UUID;

/** What the API returns for a work order. Grows with the work order API in step 1.4. */
public record WorkOrderView(
        UUID id, String title, WorkOrderStatus status, String siteAddress, Money budget, Instant createdAt) {

    static WorkOrderView of(WorkOrder workOrder) {
        return new WorkOrderView(
                workOrder.id(),
                workOrder.title(),
                workOrder.status(),
                workOrder.siteAddress(),
                workOrder.budget(),
                workOrder.createdAt());
    }
}
