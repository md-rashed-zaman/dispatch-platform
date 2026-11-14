package dev.rashed.dispatch.notification;

import dev.rashed.dispatch.outbox.Topics;
import dev.rashed.dispatch.tenancy.TenantDirectory;
import java.util.UUID;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Emails the company when its work order is taken, when the work is ready for approval, and when nobody has
 * taken it for too long (SLA breach). Reads the work
 * order events from Kafka, like a separate notification service would: it shares no transaction or memory
 * with the code that changed the work order.
 *
 * <p>Delivery is at least once, so it deduplicates by event id. Failures are retried with backoff; events
 * that can never work go to the dead-letter topic ({@link NotificationKafkaConfig}). The email itself is
 * queued, not sent: {@link EmailSender} delivers it, so a mail server outage never blocks this consumer.
 */
@Component
class CompanyNotifications {

    static final String CONSUMER = "notification";

    private static final Logger log = LoggerFactory.getLogger(CompanyNotifications.class);

    private final JdbcClient jdbc;
    private final WorkOrderEventReader events;
    private final TenantDirectory tenants;
    private final EmailTemplates templates;
    private final EmailOutbox emails;
    private final TransactionTemplate transaction;

    CompanyNotifications(
            JdbcClient jdbc,
            WorkOrderEventReader events,
            TenantDirectory tenants,
            EmailTemplates templates,
            EmailOutbox emails,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.events = events;
        this.tenants = tenants;
        this.templates = templates;
        this.emails = emails;
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
        var event = events.read(record.value());
        if (event == null) {
            return; // an event type this consumer doesn't care about
        }
        var contact = tenants.notificationContact(event.tenantId()).orElse(null);
        if (contact == null) {
            return; // the company hasn't given a notification address
        }
        var email = templates.forCompany(event, contact.companyName(), contact.email());
        if (email == null) {
            return; // a change the company isn't told about
        }
        transaction.executeWithoutResult(status -> {
            if (!firstDelivery(event.eventId())) {
                log.info("Skipped event {}: already processed", event.eventId());
                return;
            }
            // Same transaction as the processed_event row: both are saved, or neither is. The email has the
            // event id as its message id, so even a second queue attempt can't make a second email.
            emails.queue(event.eventId(), email);
        });
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
    @SchedulerLock(name = "processed-event-cleanup", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    void deleteOldEntries() {
        var deleted = jdbc.sql("delete from processed_event where processed_at < now() - interval '14 days'")
                .update();
        log.info("Deleted {} processed-event entries older than two weeks", deleted);
    }
}
