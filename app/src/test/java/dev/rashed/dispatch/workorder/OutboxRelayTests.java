package dev.rashed.dispatch.workorder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.jayway.jsonpath.JsonPath;
import dev.rashed.dispatch.KafkaTestcontainersConfiguration;
import dev.rashed.dispatch.TestTechnicians;
import dev.rashed.dispatch.TestcontainersConfiguration;
import dev.rashed.dispatch.outbox.Topics;
import dev.rashed.dispatch.tenancy.TenantContext;
import io.micrometer.tracing.Tracer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Supplier;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.kafka.KafkaContainer;

/** Events reach Kafka through the outbox: in order per work order, and never lost while Kafka is down. */
@Import({TestcontainersConfiguration.class, KafkaTestcontainersConfiguration.class})
@SpringBootTest
@AutoConfigureTracing // tests run with a no-op tracer unless they ask for the real one
class OutboxRelayTests {

    @Autowired
    WorkOrderRepository workOrders;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate transaction;

    @Autowired
    KafkaContainer kafka;

    @Autowired
    Tracer tracer;

    final UUID tenant = UUID.randomUUID();
    KafkaConsumer<String, String> consumer;
    final List<ConsumerRecord<String, String>> received = new ArrayList<>();

    @BeforeEach
    void setUp() {
        jdbc.update("insert into company (id, name, slug) values (?, 'Company', ?)", tenant, "company-" + tenant);
        var config = new Properties();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumer = new KafkaConsumer<>(config, new StringDeserializer(), new StringDeserializer());
        consumer.subscribe(List.of(Topics.WORK_ORDER_EVENTS));
    }

    @AfterEach
    void tearDown() {
        consumer.close();
    }

    @Test
    void aLifecycleChangeArrivesAsAVersionedEnvelopeKeyedByTheWorkOrder() {
        var workOrder = publishedWorkOrder();

        var record = awaitEvents(workOrder, 1).getFirst();

        assertThat(record.key()).isEqualTo(workOrder.toString());
        assertThat(header(record, "eventType")).isEqualTo("work-order.status-changed");
        Map<String, Object> envelope = JsonPath.read(record.value(), "$");
        assertThat(envelope)
                .containsEntry("type", "work-order.status-changed")
                .containsEntry("version", 1)
                .containsEntry("tenantId", tenant.toString())
                .containsEntry("aggregateId", workOrder.toString())
                .containsEntry("eventId", header(record, "eventId"));
        assertThat((Map<String, Object>) JsonPath.read(record.value(), "$.payload"))
                .containsEntry("from", "DRAFT")
                .containsEntry("to", "PUBLISHED");
        assertThat((String) JsonPath.read(record.value(), "$.occurredAt")).matches("\\d{4}-\\d{2}-\\d{2}T.*Z");
    }

    @Test
    void oneWorkOrdersEventsArriveInTheOrderTheyHappened() {
        var workOrder = publishedWorkOrder();
        change(workOrder, WorkOrder::route);
        change(workOrder, WorkOrder::cancel);

        var events = awaitEvents(workOrder, 3);

        assertThat(events)
                .extracting(r -> (String) JsonPath.read(r.value(), "$.payload.to"))
                .containsExactly("PUBLISHED", "ROUTED", "CANCELLED");
        assertThat(events)
                .extracting(ConsumerRecord::partition)
                .containsOnly(events.getFirst().partition());
    }

    /** The point of the outbox: with Kafka down the change still commits, and the event follows later. */
    @Test
    void whileKafkaIsDownEventsWaitInTheOutboxAndArriveWhenItsBack() {
        var docker = kafka.getDockerClient();
        docker.pauseContainerCmd(kafka.getContainerId()).exec();
        UUID workOrder;
        try {
            workOrder = publishedWorkOrder(); // succeeds: publishing doesn't wait for Kafka
            await().during(Duration.ofSeconds(3))
                    .atMost(Duration.ofSeconds(6))
                    .until(() -> unpublished(workOrder) == 1);
        } finally {
            docker.unpauseContainerCmd(kafka.getContainerId()).exec();
        }

        assertThat(awaitEvents(workOrder, 1)).hasSize(1);
        await().atMost(Duration.ofSeconds(10)).until(() -> unpublished(workOrder) == 0);
    }

    /**
     * The request's trace goes with the event: stored in the outbox row, continued by the relay, and carried
     * in the Kafka message's traceparent header, so consumers join the same trace (T25).
     */
    @Test
    void theEventCarriesTheTraceOfTheRequestThatPublishedIt() {
        var request = tracer.nextSpan().name("test request").start();
        UUID workOrder;
        try (var scope = tracer.withSpan(request)) {
            workOrder = publishedWorkOrder();
        } finally {
            request.end();
        }
        var traceId = request.context().traceId();

        var stored = jdbc.queryForObject(
                "select trace_parent from outbox_event where message_key = ? order by id limit 1",
                String.class,
                workOrder.toString());
        assertThat(stored).startsWith("00-" + traceId + "-");
        var record = awaitEvents(workOrder, 1).getFirst();
        assertThat(header(record, "traceparent"))
                .startsWith("00-" + traceId + "-")
                .isNotEqualTo(stored);
    }

    private UUID publishedWorkOrder() {
        return asTenant(() -> {
            var draft = WorkOrder.draft(
                    tenant,
                    "Fix POS terminal",
                    "Terminal shows a communication error.",
                    "Dhanmondi, Dhaka",
                    new dev.rashed.dispatch.shared.GeoPoint(23.7465, 90.3760),
                    List.of(TestTechnicians.uniqueSkill()), // nobody matches, so matching leaves it alone
                    WorkOrders.draft(tenant, "x").timeWindow(),
                    dev.rashed.dispatch.shared.Money.bdt(250_000));
            draft.addChecklistItem("Photo of the screen");
            draft = workOrders.saveAndFlush(draft);
            draft.publish();
            return workOrders.save(draft).id();
        });
    }

    private void change(UUID workOrder, java.util.function.Consumer<WorkOrder> action) {
        asTenant(() -> {
            var loaded = workOrders.findById(workOrder).orElseThrow();
            action.accept(loaded);
            return workOrders.save(loaded);
        });
    }

    /** Polls until {@code count} events for the work order arrived, then a moment longer to catch extras. */
    private List<ConsumerRecord<String, String>> awaitEvents(UUID workOrder, int count) {
        await().atMost(Duration.ofSeconds(20)).until(() -> {
            consumer.poll(Duration.ofMillis(200)).forEach(received::add);
            return forWorkOrder(workOrder).size() >= count;
        });
        consumer.poll(Duration.ofMillis(500)).forEach(received::add);
        return forWorkOrder(workOrder);
    }

    private List<ConsumerRecord<String, String>> forWorkOrder(UUID workOrder) {
        return received.stream()
                .filter(r -> workOrder.toString().equals(r.key()))
                .toList();
    }

    private long unpublished(UUID workOrder) {
        return jdbc.queryForObject(
                "select count(*) from outbox_event where message_key = ? and published_at is null",
                Long.class,
                workOrder.toString());
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }

    private <T> T asTenant(Supplier<T> work) {
        return TenantContext.callAs(tenant, () -> transaction.execute(s -> work.get()));
    }
}
