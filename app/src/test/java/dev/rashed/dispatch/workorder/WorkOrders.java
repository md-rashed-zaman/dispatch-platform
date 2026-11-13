package dev.rashed.dispatch.workorder;

import dev.rashed.dispatch.shared.GeoPoint;
import dev.rashed.dispatch.shared.Money;
import dev.rashed.dispatch.shared.TimeWindow;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/** Test data builder: a valid draft, so each test only spells out what it cares about. */
final class WorkOrders {

    static WorkOrder draft(UUID tenantId, String title) {
        var start = Instant.parse("2026-12-01T04:00:00Z"); // 10:00 in Dhaka
        return WorkOrder.draft(
                tenantId,
                title,
                "POS terminal at the counter shows 'communication error' since this morning.",
                "House 12, Road 5, Dhanmondi, Dhaka",
                new GeoPoint(23.7465, 90.3760),
                List.of("pos-terminal", "networking"),
                new TimeWindow(start, start.plus(Duration.ofHours(4))),
                Money.bdt(250_000));
    }

    /**
     * A work order in {@code status}, reached through the real transitions (no shortcuts past the rules).
     * Cancelled ones are cancelled from DRAFT.
     */
    static WorkOrder inStatus(WorkOrderStatus status) {
        return inStatus(UUID.randomUUID(), status);
    }

    static WorkOrder inStatus(UUID tenantId, WorkOrderStatus status) {
        return inStatus(tenantId, UUID.randomUUID(), status);
    }

    /** {@code technicianId} must exist in the database if the work order is saved past ROUTED. */
    static WorkOrder inStatus(UUID tenantId, UUID technicianId, WorkOrderStatus status) {
        var workOrder = draft(tenantId, "Fix POS terminal");
        workOrder.addChecklistItem("Photo of the terminal's error screen");
        if (status == WorkOrderStatus.CANCELLED) {
            workOrder.cancel();
            return workOrder;
        }
        List<Consumer<WorkOrder>> path = List.of(
                WorkOrder::publish,
                WorkOrder::route,
                w -> w.assign(technicianId),
                WorkOrder::start,
                WorkOrder::submit,
                WorkOrder::approve,
                WorkOrder::markPaid);
        for (var step : path) {
            if (workOrder.status() == status) {
                break;
            }
            step.accept(workOrder);
        }
        return workOrder;
    }

    private WorkOrders() {}
}
