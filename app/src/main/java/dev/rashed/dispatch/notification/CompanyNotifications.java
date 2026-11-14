package dev.rashed.dispatch.notification;

import dev.rashed.dispatch.outbox.OutboxEnvelope;
import dev.rashed.dispatch.outbox.Topics;
import dev.rashed.dispatch.workorder.WorkOrderStatusChangedV1;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tells the company when its work order is taken and when the work is ready for approval. Reads the work
 * order events from Kafka, like a separate notification service would: it shares no transaction or memory
 * with the code that changed the work order.
 *
 * <p>Delivery is at least once, so it deduplicates by event id. Failures are retried with backoff; events
 * that can never work go to the dead-letter topic ({@link NotificationKafkaConfig}).
 */
@Component
class CompanyNotifications {

    static final String CONSUMER = "notification";

    private static final Logger log = LoggerFactory.getLogger(CompanyNotifications.class);

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final Notifier notifier;
    private final TransactionTemplate transaction;

    CompanyNotifications(
            JdbcClient jdbc, JsonMapper json, Notifier notifier, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.json = json;
        this.notifier = notifier;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    // The offset is committed only after this returns, so a crash halfway means the event comes again; the
    // processed_event row makes that harmless.
    @KafkaListener(
            id = CONSUMER,
            groupId = "dispatch-notification",
            topics = Topics.WORK_ORDER_EVENTS,
            autoStartup = "${dispatch.notification.consumer-enabled:true}")
    void on(ConsumerRecord<String, String> record) {
        var event = parse(record.value());
        if (event == null) {
            return; // an event type this consumer doesn't care about
        }
        var message = messageFor(event.payload());
        if (message == null) {
            return;
        }
        transaction.executeWithoutResult(status -> {
            if (!firstDelivery(event.eventId())) {
                log.info("Skipped event {}: already processed", event.eventId());
                return;
            }
            // If this throws, the processed_event row rolls back too: the retry isn't taken for a duplicate.
            // shortcut: an email sent just before a crash is sent again; real email gets eventId as the
            // provider's idempotency key.
            notifier.notifyCompany(event.tenantId(), message);
        });
    }

    private @Nullable StatusChanged parse(@Nullable String value) {
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
            return null; // new event types on this topic must not break old consumers
        }
        if (envelope.version() != WorkOrderStatusChangedV1.VERSION) {
            throw new MalformedEventException("Unknown version " + envelope.version() + " of " + envelope.type());
        }
        WorkOrderStatusChangedV1 payload;
        try {
            payload = json.convertValue(envelope.payload(), WorkOrderStatusChangedV1.class);
        } catch (RuntimeException e) {
            throw new MalformedEventException("Unreadable " + envelope.type() + " payload", e);
        }
        if (payload == null || payload.workOrderId() == null || payload.to() == null) {
            throw new MalformedEventException("Payload without workOrderId or status");
        }
        return new StatusChanged(envelope.eventId(), envelope.tenantId(), payload);
    }

    private static @Nullable String messageFor(WorkOrderStatusChangedV1 change) {
        return switch (change.to()) {
            case ASSIGNED -> "A technician accepted work order " + change.workOrderId();
            case SUBMITTED -> "Work order " + change.workOrderId() + " is done and waiting for your approval";
            default -> null;
        };
    }

    private boolean firstDelivery(UUID eventId) {
        return jdbc.sql("""
                        insert into processed_event (consumer, event_id) values (:consumer, :eventId)
                        on conflict do nothing
                        """)
                        .param("consumer", CONSUMER)
                        .param("eventId", eventId)
                        .update()
                == 1;
    }

    /** Kafka keeps events a week, so nothing older than two weeks can come again. */
    @Scheduled(cron = "0 30 3 * * *")
    void deleteOldEntries() {
        var deleted = jdbc.sql("delete from processed_event where processed_at < now() - interval '14 days'")
                .update();
        log.info("Deleted {} processed-event entries older than two weeks", deleted);
    }

    private record StatusChanged(UUID eventId, UUID tenantId, WorkOrderStatusChangedV1 payload) {}
}
