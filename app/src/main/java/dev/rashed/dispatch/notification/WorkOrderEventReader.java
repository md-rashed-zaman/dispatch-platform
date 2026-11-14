package dev.rashed.dispatch.notification;

import dev.rashed.dispatch.outbox.OutboxEnvelope;
import dev.rashed.dispatch.workorder.WorkOrderStatusChangedV1;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Reads work order events from Kafka for this module's consumers. */
@Component
class WorkOrderEventReader {

    record StatusChange(UUID eventId, UUID tenantId, WorkOrderStatusChangedV1 change) {}

    private final JsonMapper json;

    WorkOrderEventReader(JsonMapper json) {
        this.json = json;
    }

    /**
     * @return the status change, or null for an event type this module doesn't handle (new event types on the
     *     topic must not break old consumers)
     * @throws MalformedEventException if the message can never be read, however often it's retried
     */
    @Nullable
    StatusChange read(@Nullable String value) {
        if (value == null) {
            throw new MalformedEventException("Empty message");
        }
        OutboxEnvelope envelope;
        try {
            envelope = json.readValue(value, OutboxEnvelope.class);
        } catch (RuntimeException e) {
            throw new MalformedEventException("Not an event envelope", e);
        }
        if (envelope == null || envelope.eventId() == null || envelope.type() == null || envelope.tenantId() == null) {
            throw new MalformedEventException("Envelope without eventId, type or tenantId");
        }
        if (!WorkOrderStatusChangedV1.TYPE.equals(envelope.type())) {
            return null;
        }
        if (envelope.version() != WorkOrderStatusChangedV1.VERSION) {
            throw new MalformedEventException("Unknown version " + envelope.version() + " of " + envelope.type());
        }
        WorkOrderStatusChangedV1 change;
        try {
            change = json.convertValue(envelope.payload(), WorkOrderStatusChangedV1.class);
        } catch (RuntimeException e) {
            throw new MalformedEventException("Unreadable " + envelope.type() + " payload", e);
        }
        if (change == null || change.workOrderId() == null || change.to() == null) {
            throw new MalformedEventException("Payload without workOrderId or status");
        }
        return new StatusChange(envelope.eventId(), envelope.tenantId(), change);
    }
}
