package dev.rashed.dispatch.workorder;

import dev.rashed.dispatch.outbox.Outbox;
import dev.rashed.dispatch.outbox.Topics;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Puts every lifecycle change on Kafka, through the outbox (ADR 0006). */
@Component
class WorkOrderEventsOutbox {

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
                WorkOrderStatusChangedV1.TYPE,
                WorkOrderStatusChangedV1.VERSION,
                event.tenantId(),
                event.occurredAt(),
                new WorkOrderStatusChangedV1(
                        event.workOrderId(),
                        event.title(),
                        event.from(),
                        event.to(),
                        event.technicianId(),
                        event.occurredAt()));
    }

    @EventListener
    void on(SlaBreached event) {
        outbox.append(
                Topics.WORK_ORDER_EVENTS,
                event.workOrderId().toString(), // same key: it stays in order with the work order's other events
                WorkOrderSlaBreachedV1.TYPE,
                WorkOrderSlaBreachedV1.VERSION,
                event.tenantId(),
                event.occurredAt(),
                new WorkOrderSlaBreachedV1(
                        event.workOrderId(), event.title(), event.waitingSince(), event.occurredAt()));
    }
}
