package dev.rashed.dispatch.outbox;

import java.time.Duration;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

/**
 * A listener factory for one consumer with its own dead-letter topic (T19): a failing record is retried 3 times
 * (1, 2, 4 s), then published to {@code deadLetterTopic} on the same partition with the exception in its headers,
 * and the consumer moves on.
 *
 * <p>Use it from a {@code @Bean} method and keep the error handler out of the context: a second error-handler bean
 * would leave Spring Boot's default factory (the notification consumer's) without one.
 */
public final class DeadLetterListeners {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterListeners.class);

    public static ConcurrentKafkaListenerContainerFactory<String, String> factory(
            ConsumerFactory<String, String> consumers, KafkaTemplate<String, String> kafka, String deadLetterTopic) {
        var deadLetters = new DeadLetterPublishingRecoverer(
                kafka, (record, exception) -> new TopicPartition(deadLetterTopic, record.partition()));
        var backOff = new ExponentialBackOffWithMaxRetries(3);
        backOff.setInitialInterval(Duration.ofSeconds(1).toMillis());
        backOff.setMultiplier(2);
        var handler = new DefaultErrorHandler(
                (record, exception) -> {
                    log.error(
                            "Event {}-{}@{} sent to {}: {}",
                            record.topic(),
                            record.partition(),
                            record.offset(),
                            deadLetterTopic,
                            NestedExceptionUtils.getMostSpecificCause(exception).toString());
                    deadLetters.accept(record, exception);
                },
                backOff);
        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(consumers);
        factory.setCommonErrorHandler(handler);
        return factory;
    }

    private DeadLetterListeners() {}
}
