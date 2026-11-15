package dev.rashed.dispatch.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sends committed outbox events to Kafka, oldest first, then marks them published. If anything fails, the
 * transaction rolls back and the whole batch is sent again next time: at least once, never lost. Consumers
 * deduplicate by event id.
 */
@Component
class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    /** Any constant; every instance uses the same one, so only one relays at a time. */
    private static final long RELAY_LOCK = 0x0D15_7A7C_0B07L;

    private final JdbcClient jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate transaction;
    private final int batchSize;
    private final Duration sendTimeout;
    private final boolean enabled;
    private final Tracer tracer;
    private final Propagator propagator;
    private int consecutiveFailures; // only the single scheduler thread touches it

    // The switch is checked at run time, not with @ConditionalOnProperty: conditions are evaluated before
    // Testcontainers registers its properties, so a test could never turn the relay on.
    OutboxRelay(
            JdbcClient jdbc,
            KafkaTemplate<String, String> kafka,
            PlatformTransactionManager transactionManager,
            @Value("${dispatch.outbox.batch-size:100}") int batchSize,
            @Value("${dispatch.outbox.send-timeout:PT10S}") Duration sendTimeout,
            @Value("${dispatch.outbox.relay-enabled:true}") boolean enabled,
            Tracer tracer,
            Propagator propagator,
            MeterRegistry meters) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.transaction = new TransactionTemplate(transactionManager);
        this.batchSize = batchSize;
        this.sendTimeout = sendTimeout;
        this.enabled = enabled;
        this.tracer = tracer;
        this.propagator = propagator;
        // The first sign the relay or Kafka is stuck (T17): events waiting more than a few seconds. Read when
        // metrics are exported (every 10 s); only unsent rows are scanned.
        Gauge.builder("dispatch.outbox.oldest_unpublished_age", this, OutboxRelay::oldestUnpublishedSeconds)
                .description("Age of the oldest event not yet sent to Kafka")
                .baseUnit("seconds")
                .register(meters);
    }

    private double oldestUnpublishedSeconds() {
        return jdbc.sql("select coalesce(extract(epoch from now() - min(created_at)), 0) from outbox_event"
                        + " where published_at is null")
                .query(Double.class)
                .single();
    }

    @Scheduled(fixedDelayString = "${dispatch.outbox.relay-interval:PT0.5S}")
    void run() {
        if (!enabled) {
            return;
        }
        try {
            int sent;
            do {
                sent = relayBatch();
            } while (sent == batchSize); // a backlog drains at full speed, then back to polling
            if (consecutiveFailures > 0) {
                log.info("Outbox relay recovered after {} failed attempts", consecutiveFailures);
                consecutiveFailures = 0;
            }
        } catch (RuntimeException e) {
            // Kafka down means a failure every interval: the first gets the full stack, then a reminder every
            // 60 attempts (30 s), so an outage doesn't bury the log.
            if (consecutiveFailures++ == 0) {
                log.warn("Outbox relay failed, retrying every interval until Kafka is back", e);
            } else if (consecutiveFailures % 60 == 0) {
                log.warn("Outbox relay still failing ({} attempts): {}", consecutiveFailures, e.getMessage());
            }
        }
    }

    /**
     * One batch in one transaction: the lock, the rows and the "published" mark commit or roll back together.
     *
     * @return how many events were sent (0 if another instance holds the relay lock or nothing is waiting)
     */
    int relayBatch() {
        return Objects.requireNonNull(transaction.execute(status -> sendOneBatch()));
    }

    private int sendOneBatch() {
        // One relay at a time across all instances. Two relays could each lock a different batch (SKIP LOCKED
        // lets them) and send one work order's events out of order. The lock is released at commit.
        if (!Boolean.TRUE.equals(jdbc.sql("select pg_try_advisory_xact_lock(:lock)")
                .param("lock", RELAY_LOCK)
                .query(Boolean.class)
                .single())) {
            return 0;
        }
        // SKIP LOCKED: rows another transaction holds are passed over instead of waited on. With the lock above
        // nobody else holds them; it keeps the query safe if the relay is ever split by key.
        var batch = jdbc.sql("""
                        select id, event_id, topic, message_key, event_type, payload::text as payload, trace_parent
                        from outbox_event where published_at is null
                        order by id limit :limit
                        for update skip locked
                        """)
                .param("limit", batchSize)
                .query((rs, rowNum) -> new Pending(
                        rs.getLong("id"),
                        rs.getObject("event_id", UUID.class),
                        rs.getString("topic"),
                        rs.getString("message_key"),
                        rs.getString("event_type"),
                        rs.getString("payload"),
                        rs.getString("trace_parent")))
                .list();
        if (batch.isEmpty()) {
            return 0;
        }
        // Send all, then wait for all: the producer pipelines them, and per key they stay in order.
        List<CompletableFuture<SendResult<String, String>>> sends = new ArrayList<>();
        for (var event : batch) {
            var record = new ProducerRecord<>(event.topic(), event.key(), event.payload());
            record.headers().add("eventId", event.eventId().toString().getBytes(StandardCharsets.UTF_8));
            record.headers().add("eventType", event.type().getBytes(StandardCharsets.UTF_8));
            sends.add(sendInTraceOf(event, record));
        }
        try {
            CompletableFuture.allOf(sends.toArray(CompletableFuture[]::new))
                    .get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("Kafka didn't confirm the batch", e); // roll back: send again later
        }
        jdbc.sql("update outbox_event set published_at = now() where id in (:ids)")
                .param("ids", batch.stream().map(Pending::id).toList())
                .update();
        return batch.size();
    }

    /**
     * Sends inside a span that continues the trace of the request that wrote the event, so the producer span
     * (and the consumers after it) join that trace instead of starting new ones.
     */
    private CompletableFuture<SendResult<String, String>> sendInTraceOf(
            Pending event, ProducerRecord<String, String> record) {
        if (event.traceParent() == null) {
            return kafka.send(record);
        }
        var span = propagator
                .extract(Map.of("traceparent", event.traceParent()), Map::get)
                .name("outbox relay")
                .tag("event.type", event.type())
                .start();
        try (var scope = tracer.withSpan(span)) {
            return kafka.send(record);
        } finally {
            span.end();
        }
    }

    /** Published events are kept a week for debugging and replays, then deleted. */
    @Scheduled(cron = "0 15 3 * * *")
    @SchedulerLock(name = "outbox-cleanup", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    void deleteOldPublished() {
        var deleted = jdbc.sql("delete from outbox_event where published_at < now() - interval '7 days'")
                .update(); // one statement: auto-commit is its own transaction
        log.info("Deleted {} published outbox events older than a week", deleted);
    }

    private record Pending(
            long id,
            UUID eventId,
            String topic,
            String key,
            String type,
            String payload,
            @Nullable String traceParent) {}
}
