package dev.rashed.dispatch.notification;

import dev.rashed.dispatch.outbox.OutboxEnvelope;
import dev.rashed.dispatch.workorder.WorkOrderSlaBreachedV1;
import dev.rashed.dispatch.workorder.WorkOrderStatusChangedV1;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Reads work order events from Kafka for this module's consumers. */
@Component
class WorkOrderEventReader {

    /** The event types this module handles. */
    sealed interface WorkOrderEvent {
        UUID eventId();

        UUID tenantId();

        String type();

        /** The payload as published, for passing on (live updates). */
        Object payload();
    }

    record StatusChange(UUID eventId, UUID tenantId, WorkOrderStatusChangedV1 payload) implements WorkOrderEvent {
        @Override
        public String type() {
            return WorkOrderStatusChangedV1.TYPE;
        }
    }

    record SlaBreach(UUID eventId, UUID tenantId, WorkOrderSlaBreachedV1 payload) implements WorkOrderEvent {
        @Override
        public String type() {
            return WorkOrderSlaBreachedV1.TYPE;
        }
    }

    private final JsonMapper json;

    WorkOrderEventReader(JsonMapper json) {
        this.json = json;
    }

    /**
     * @return the event, or null for an event type this module doesn't handle (new event types on the topic
     *     must not break old consumers)
     * @throws MalformedEventException if the message can never be read, however often it's retried
     */
    @Nullable
    WorkOrderEvent read(@Nullable String value) {
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
        return switch (envelope.type()) {
            case WorkOrderStatusChangedV1.TYPE -> {
                var change = payload(envelope, WorkOrderStatusChangedV1.VERSION, WorkOrderStatusChangedV1.class);
                if (change.workOrderId() == null || change.to() == null) {
                    throw new MalformedEventException("Payload without workOrderId or status");
                }
                yield new StatusChange(envelope.eventId(), envelope.tenantId(), change);
            }
            case WorkOrderSlaBreachedV1.TYPE -> {
                var breach = payload(envelope, WorkOrderSlaBreachedV1.VERSION, WorkOrderSlaBreachedV1.class);
                if (breach.workOrderId() == null) {
                    throw new MalformedEventException("Payload without workOrderId");
                }
                yield new SlaBreach(envelope.eventId(), envelope.tenantId(), breach);
            }
            default -> null;
        };
    }

    private <T> T payload(OutboxEnvelope envelope, int version, Class<T> type) {
        if (envelope.version() != version) {
            throw new MalformedEventException("Unknown version " + envelope.version() + " of " + envelope.type());
        }
        T payload;
        try {
            payload = json.convertValue(envelope.payload(), type);
        } catch (RuntimeException e) {
            throw new MalformedEventException("Unreadable " + envelope.type() + " payload", e);
        }
        if (payload == null) {
            throw new MalformedEventException("Empty " + envelope.type() + " payload");
        }
        return payload;
    }
}
