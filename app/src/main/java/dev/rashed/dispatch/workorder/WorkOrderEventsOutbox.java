package dev.rashed.dispatch.workorder;

import dev.rashed.dispatch.outbox.Outbox;
import dev.rashed.dispatch.outbox.Topics;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Puts every lifecycle change on Kafka, through the outbox (ADR 0006). */
@Component
class WorkOrderEventsOutbox {

    static final String STATUS_CHANGED = "work-order.status-changed";

    private final Outbox outbox;

    WorkOrderEventsOutbox(Outbox outbox) {
        this.outbox = outbox;
    }

    // A plain @EventListener runs synchronously, inside the transaction that saves the work order. That's
    // the point: the outbox row commits or rolls back with the change. (After-commit would be too late.)
    @EventListener
    void on(WorkOrderStatusChanged event) {
        outbox.append(
                Topics.WORK_ORDER_EVENTS,
                event.workOrderId().toString(),
                STATUS_CHANGED,
                1,
                event.tenantId(),
                event.occurredAt(),
                new WorkOrderStatusChangedV1(
                        event.workOrderId(), event.from(), event.to(), event.technicianId(), event.occurredAt()));
    }
}
