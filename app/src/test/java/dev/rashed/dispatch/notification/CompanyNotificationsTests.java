package dev.rashed.dispatch.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doCallRealMethod;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import tools.jackson.databind.json.JsonMapper;

/**
 * The consumer survives what Kafka really delivers: the same event twice, a broken message, a failure that
 * passes, and one that doesn't. Events are put on the topic directly, so each test controls exactly what
 * arrives. Each test has its own company and work order, so they don't see each other's events.
 */
@Import({TestcontainersConfiguration.class, KafkaTestcontainersConfiguration.class})
@SpringBootTest
class CompanyNotificationsTests {

    private static final Duration WAIT = Duration.ofSeconds(20);

    @MockitoSpyBean // real queueing, with failures injected where a test needs them
    EmailOutbox emails;

    @MockitoSpyBean // to count how often the consumer reads a message
    JsonMapper json;

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    ConsumerFactory<String, String> consumers;

    @Autowired
    JdbcClient jdbc;

    final UUID tenant = UUID.randomUUID();
    final UUID workOrder = UUID.randomUUID();
    final String recipient = "dispatch-" + tenant + "@bank.example";
    final List<UUID> sentEventIds = new ArrayList<>();

    @BeforeEach
    void aCompanyWithANotificationAddress() {
        jdbc.sql("insert into company (id, name, slug, notification_email) values (?, 'Meghna Bank PLC', ?, ?)")
                .params(tenant, "bank-" + tenant, recipient)
                .update();
    }

    @Test
    void theCompanyGetsAnEmailWithTheTitleAStatusAndALink() {
        send(event(UUID.randomUUID(), WorkOrderStatus.SUBMITTED));

        var email = awaitEmails(1).getFirst();
        assertThat(email.recipient()).isEqualTo(recipient);
        assertThat(email.subject()).isEqualTo("Ready for your approval: CCTV camera offline");
        assertThat(email.text())
                .contains("The work is done and waiting for your approval")
                .contains("Review and approve: http://localhost:5173/work-orders/" + workOrder)
                .contains(recipient + " is the notification address for Meghna Bank PLC");
        assertThat(email.html())
                .contains("Waiting for approval")
                .contains("href=\"http://localhost:5173/work-orders/" + workOrder + "\"")
                .doesNotContain(" th:"); // every template attribute was processed
    }

    @Test
    void theSameEventDeliveredTwiceSendsOneEmail() {
        var assigned = event(UUID.randomUUID(), WorkOrderStatus.ASSIGNED);
        send(assigned);
        send(assigned); // what a relay resend or a crash before the offset commit looks like
        send(event(UUID.randomUUID(), WorkOrderStatus.SUBMITTED)); // same key, so it arrives after both

        await().atMost(WAIT).until(() -> processedCount() == 2);
        assertThat(awaitEmails(2))
                .extracting(OutboxEmail::subject)
                .containsExactly(
                        "A technician accepted CCTV camera offline", "Ready for your approval: CCTV camera offline");
    }

    @Test
    void aMalformedEventGoesToTheDeadLetterTopicAtOnceAndTheConsumerKeepsGoing() {
        var malformed = "{ this is not json " + workOrder;
        send(malformed);
        send(event(UUID.randomUUID(), WorkOrderStatus.SUBMITTED));

        awaitEmails(1);
        var dead = awaitDeadLetter();
        assertThat(dead.value()).isEqualTo(malformed);
        // Once by each consumer (notification and live updates), and no retries.
        verify(json, times(2)).readValue(malformed, OutboxEnvelope.class);
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
        sentEventIds.addAll(List.of(future.eventId(), v2.eventId()));

        assertThat(awaitDeadLetter().value()).contains("\"version\":2");
        assertThat(emailsAbout()).isEmpty();
    }

    @Test
    void aCompanyWithoutANotificationAddressGetsNoEmail() {
        jdbc.sql("update company set notification_email = null where id = ?")
                .param(tenant)
                .update();
        var unnoticed = UUID.randomUUID();
        send(event(unnoticed, WorkOrderStatus.SUBMITTED));
        // Nothing appears, so wait until the consumer has read it, then make sure nothing follows.
        verify(json, timeout(WAIT.toMillis()).atLeastOnce())
                .readValue(contains(unnoticed.toString()), eq(OutboxEnvelope.class));

        await().during(Duration.ofSeconds(1))
                .atMost(Duration.ofSeconds(3))
                .until(() -> emailsAbout().isEmpty());
        assertThat(processedCount()).isZero();
    }

    @Test
    void aShortFailureIsRetriedAndQueuesOneEmail() {
        var eventId = UUID.randomUUID();
        doThrow(new IllegalStateException("database blip"))
                .doThrow(new IllegalStateException("database blip"))
                .doCallRealMethod()
                .when(outbox())
                .queue(eq(eventId), any());

        send(event(eventId, WorkOrderStatus.SUBMITTED));

        // Two failures, then success. The failed attempts rolled back their processed_event row each time.
        verify(outbox(), timeout(WAIT.toMillis()).times(3)).queue(eq(eventId), any());
        assertThat(awaitEmails(1)).hasSize(1);
        assertThat(processedCount()).isEqualTo(1);
    }

    @Test
    void aFailureThatLastsGoesToTheDeadLetterTopicAfterRetriesAndTheConsumerKeepsGoing() {
        var failing = UUID.randomUUID();
        doThrow(new IllegalStateException("database down")).when(outbox()).queue(eq(failing), any());

        send(event(failing, WorkOrderStatus.SUBMITTED));
        send(event(UUID.randomUUID(), WorkOrderStatus.ASSIGNED));

        var dead = awaitDeadLetter();
        verify(outbox(), times(5)).queue(eq(failing), any()); // the first try and 4 retries
        assertThat(header(dead, "kafka_dlt-exception-cause-fqcn")).isEqualTo(IllegalStateException.class.getName());
        assertThat(header(dead, "kafka_dlt-exception-message")).contains("database down");
        assertThat(awaitEmails(1))
                .extracting(OutboxEmail::subject)
                .containsExactly("A technician accepted CCTV camera offline");
        assertThat(processedCount()).isEqualTo(1); // only the one that worked
        doCallRealMethod().when(outbox()).queue(eq(failing), any());
    }

    /**
     * The spy sits behind the transaction proxy: stubbing through the proxy would run its MANDATORY check
     * (no transaction in the test thread), so stub and verify the spy itself.
     */
    private EmailOutbox outbox() {
        return AopTestUtils.getUltimateTargetObject(emails);
    }

    record OutboxEmail(String recipient, String subject, String text, String html) {}

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
        return new WorkOrderStatusChangedV1(
                workOrder, "CCTV camera offline", WorkOrderStatus.ROUTED, to, null, Instant.now());
    }

    private void send(String value) {
        kafka.send(new ProducerRecord<>(Topics.WORK_ORDER_EVENTS, workOrder.toString(), value))
                .join();
    }

    private List<OutboxEmail> awaitEmails(int count) {
        await().atMost(WAIT).until(() -> emailsAbout().size() >= count);
        return emailsAbout();
    }

    private List<OutboxEmail> emailsAbout() {
        return jdbc.sql("""
                        select recipient, subject, text_body, html_body from outgoing_email
                        where message_id in (:ids) order by id
                        """)
                .param("ids", sentEventIds)
                .query((rs, n) -> new OutboxEmail(
                        rs.getString("recipient"),
                        rs.getString("subject"),
                        rs.getString("text_body"),
                        rs.getString("html_body")))
                .list();
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
