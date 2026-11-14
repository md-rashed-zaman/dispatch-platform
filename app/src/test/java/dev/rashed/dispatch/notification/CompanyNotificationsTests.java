package dev.rashed.dispatch.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import dev.rashed.dispatch.KafkaTestcontainersConfiguration;
import dev.rashed.dispatch.TestcontainersConfiguration;
import dev.rashed.dispatch.outbox.OutboxEnvelope;
import dev.rashed.dispatch.outbox.Topics;
import dev.rashed.dispatch.workorder.WorkOrderStatus;
import dev.rashed.dispatch.workorder.WorkOrderStatusChangedV1;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.json.JsonMapper;

/**
 * The consumer survives what Kafka really delivers: the same event twice, a broken message, a dependency
 * that's down for a moment, and one that stays down. Events are put on the topic directly, so each test
 * controls exactly what arrives. Each test uses its own tenant and work order, so they don't see each other.
 */
@Import({TestcontainersConfiguration.class, KafkaTestcontainersConfiguration.class})
@SpringBootTest
class CompanyNotificationsTests {

    private static final Duration WAIT = Duration.ofSeconds(20);

    @MockitoBean
    Notifier notifier;

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    ConsumerFactory<String, String> consumers;

    @MockitoSpyBean // to count how often the consumer reads a message
    JsonMapper json;

    @Autowired
    JdbcClient jdbc;

    final UUID tenant = UUID.randomUUID();
    final UUID workOrder = UUID.randomUUID();
    final List<UUID> sentEventIds = new ArrayList<>();

    @Test
    void theSameEventDeliveredTwiceNotifiesOnce() {
        var assigned = event(UUID.randomUUID(), WorkOrderStatus.ASSIGNED);
        send(assigned);
        send(assigned); // what a relay resend or a crash before the offset commit looks like
        send(event(UUID.randomUUID(), WorkOrderStatus.SUBMITTED)); // same key, so it arrives after both

        verify(notifier, timeout(WAIT.toMillis())).notifyCompany(eq(tenant), eq(submittedMessage()));
        verify(notifier, times(1)).notifyCompany(tenant, "A technician accepted work order " + workOrder);
        assertThat(processedCount()).isEqualTo(2);
    }

    @Test
    void aMalformedEventGoesToTheDeadLetterTopicAtOnceAndTheConsumerKeepsGoing() {
        var malformed = "{ this is not json " + workOrder;
        send(malformed);
        send(event(UUID.randomUUID(), WorkOrderStatus.SUBMITTED));

        verify(notifier, timeout(WAIT.toMillis())).notifyCompany(tenant, submittedMessage());
        var dead = awaitDeadLetter();
        assertThat(dead.value()).isEqualTo(malformed);
        verify(json, times(1)).readValue(malformed, OutboxEnvelope.class); // read once: no retries
        assertThat(header(dead, "kafka_dlt-exception-cause-fqcn")).isEqualTo(MalformedEventException.class.getName());
        assertThat(header(dead, "kafka_dlt-original-topic")).isEqualTo(Topics.WORK_ORDER_EVENTS);
    }

    @Test
    void anUnknownVersionIsMalformedButAnUnknownTypeIsIgnored() {
        var future = new OutboxEnvelope(
                UUID.randomUUID(), "work-order.rated", 1, Instant.now(), tenant, workOrder.toString(), "{}");
        send(json.writeValueAsString(future)); // a new event type: old consumers must ignore it, not fail
        var v2 = new OutboxEnvelope(
                UUID.randomUUID(),
                WorkOrderStatusChangedV1.TYPE,
                2,
                Instant.now(),
                tenant,
                workOrder.toString(),
                payload(WorkOrderStatus.SUBMITTED));
        send(json.writeValueAsString(v2));

        assertThat(awaitDeadLetter().value()).contains("\"version\":2");
        verify(notifier, times(0)).notifyCompany(eq(tenant), anyString());
    }

    @Test
    void aShortOutageIsRetriedAndTheMessageIsSentOnce() {
        doThrow(new IllegalStateException("mail server down"))
                .doThrow(new IllegalStateException("mail server down"))
                .doNothing()
                .when(notifier)
                .notifyCompany(eq(tenant), anyString());

        send(event(UUID.randomUUID(), WorkOrderStatus.SUBMITTED));

        // Two failures, then success. The failed attempts rolled back their processed_event row each time.
        verify(notifier, timeout(WAIT.toMillis()).times(3)).notifyCompany(tenant, submittedMessage());
        assertThat(processedCount()).isEqualTo(1);
    }

    @Test
    void aFailureThatLastsGoesToTheDeadLetterTopicAfterRetriesAndTheConsumerKeepsGoing() {
        doThrow(new IllegalStateException("mail server down")).when(notifier).notifyCompany(tenant, submittedMessage());

        send(event(UUID.randomUUID(), WorkOrderStatus.SUBMITTED));
        send(event(UUID.randomUUID(), WorkOrderStatus.ASSIGNED));

        var dead = awaitDeadLetter();
        verify(notifier, times(5)).notifyCompany(tenant, submittedMessage()); // the first try and 4 retries
        assertThat(header(dead, "kafka_dlt-exception-cause-fqcn")).isEqualTo(IllegalStateException.class.getName());
        assertThat(header(dead, "kafka_dlt-exception-message")).contains("mail server down");
        verify(notifier, timeout(WAIT.toMillis()))
                .notifyCompany(tenant, "A technician accepted work order " + workOrder);
        assertThat(processedCount()).isEqualTo(1); // only the one that worked
        doNothing().when(notifier).notifyCompany(eq(tenant), anyString());
    }

    private String event(UUID eventId, WorkOrderStatus to) {
        sentEventIds.add(eventId);
        return json.writeValueAsString(new OutboxEnvelope(
                eventId,
                WorkOrderStatusChangedV1.TYPE,
                WorkOrderStatusChangedV1.VERSION,
                Instant.now(),
                tenant,
                workOrder.toString(),
                payload(to)));
    }

    private WorkOrderStatusChangedV1 payload(WorkOrderStatus to) {
        return new WorkOrderStatusChangedV1(workOrder, WorkOrderStatus.ROUTED, to, null, Instant.now());
    }

    private String submittedMessage() {
        return "Work order " + workOrder + " is done and waiting for your approval";
    }

    private void send(String value) {
        kafka.send(new ProducerRecord<>(Topics.WORK_ORDER_EVENTS, workOrder.toString(), value))
                .join();
    }

    private ConsumerRecord<String, String> awaitDeadLetter() {
        try (Consumer<String, String> consumer = consumers.createConsumer("test-" + UUID.randomUUID(), null)) {
            consumer.subscribe(List.of(NotificationKafkaConfig.DEAD_LETTER_TOPIC));
            var deadline = Instant.now().plus(WAIT);
            while (Instant.now().isBefore(deadline)) {
                for (var record : consumer.poll(Duration.ofMillis(500))) {
                    if (workOrder.toString().equals(record.key())) {
                        return record;
                    }
                }
            }
        }
        throw new AssertionError("Nothing for work order " + workOrder + " on the dead-letter topic");
    }

    private int processedCount() {
        return jdbc.sql("select count(*) from processed_event where consumer = :consumer and event_id in (:ids)")
                .param("consumer", CompanyNotifications.CONSUMER)
                .param("ids", sentEventIds)
                .query(Integer.class)
                .single();
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        var header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
