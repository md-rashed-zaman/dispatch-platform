package dev.rashed.dispatch.notification;

import dev.rashed.dispatch.outbox.Topics;
import java.time.Duration;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

/** What happens when processing an event fails (T19). */
@Configuration
class NotificationKafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(NotificationKafkaConfig.class);

    /**
     * Per consumer, not spring-kafka's shared {@code <topic>-dlt}: when a second consumer of the same topic
     * exists, each team sees, and replays, only its own failures.
     */
    static final String DEAD_LETTER_TOPIC = Topics.WORK_ORDER_EVENTS + "." + CompanyNotifications.CONSUMER + "-dlt";

    // Same partition count as the source: the recoverer keeps each record's partition.
    @Bean
    NewTopic notificationDeadLetters(
            @Value("${dispatch.outbox.partitions:6}") int partitions,
            @Value("${dispatch.outbox.replicas:1}") int replicas) {
        return TopicBuilder.name(DEAD_LETTER_TOPIC)
                .partitions(partitions)
                .replicas(replicas)
                .build();
    }

    /**
     * A failing event is retried in place with exponential backoff (1 s, 2 s, 4 s, 8 s), then published to
     * the dead-letter topic with the exception in its headers, and the consumer moves on. A malformed event
     * goes there at once: retrying can't fix it, and every retry holds up its partition.
     */
    // shortcut: one handler for every listener in the app; give each consumer its own container factory when
    // there's a second one with a different dead-letter topic.
    @Bean
    DefaultErrorHandler kafkaErrorHandler(
            KafkaTemplate<String, String> kafka,
            @Value("${dispatch.notification.retry.initial-interval:PT1S}") Duration initialInterval,
            @Value("${dispatch.notification.retry.max-retries:4}") int maxRetries) {
        var deadLetters = new DeadLetterPublishingRecoverer(
                kafka, (record, exception) -> new TopicPartition(DEAD_LETTER_TOPIC, record.partition()));
        // The handler moves on silently, so say it here: a dead letter needs a person to look at it.
        ConsumerRecordRecoverer recoverer = (record, exception) -> {
            log.error(
                    "Event {}-{}@{} (key {}) sent to {}: {}",
                    record.topic(),
                    record.partition(),
                    record.offset(),
                    record.key(),
                    DEAD_LETTER_TOPIC,
                    NestedExceptionUtils.getMostSpecificCause(exception).toString());
            deadLetters.accept(record, exception);
        };
        var backOff = new ExponentialBackOffWithMaxRetries(maxRetries);
        backOff.setInitialInterval(initialInterval.toMillis());
        backOff.setMultiplier(2);
        backOff.setMaxInterval(Duration.ofSeconds(10).toMillis());
        var handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(MalformedEventException.class);
        return handler;
    }
}
