package dev.rashed.dispatch.matching;

import dev.rashed.dispatch.outbox.Topics;
import java.time.Duration;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

@Configuration
@EnableConfigurationProperties(MatchingProperties.class)
class MatchingConfig {

    private static final Logger log = LoggerFactory.getLogger(MatchingConfig.class);

    static final String EMBEDDER_DEAD_LETTERS = Topics.TECHNICIAN_EVENTS + "." + TechnicianEmbedder.CONSUMER + "-dlt";

    @Bean
    NewTopic technicianEmbedderDeadLetters(
            @Value("${dispatch.outbox.partitions:6}") int partitions,
            @Value("${dispatch.outbox.replicas:1}") int replicas) {
        return TopicBuilder.name(EMBEDDER_DEAD_LETTERS)
                .partitions(partitions)
                .replicas(replicas)
                .build();
    }

    /**
     * The embedder's own listener factory, so its failures go to its own dead-letter topic (T19). The error
     * handler is built here and not a bean: a second error-handler bean would leave Spring Boot's default factory,
     * the notification consumer's, without one.
     */
    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> embedderListenerFactory(
            ConsumerFactory<String, String> consumers, KafkaTemplate<String, String> kafka) {
        var deadLetters = new DeadLetterPublishingRecoverer(
                kafka, (record, exception) -> new TopicPartition(EMBEDDER_DEAD_LETTERS, record.partition()));
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
                            EMBEDDER_DEAD_LETTERS,
                            NestedExceptionUtils.getMostSpecificCause(exception).toString());
                    deadLetters.accept(record, exception);
                },
                backOff);
        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(consumers);
        factory.setCommonErrorHandler(handler);
        return factory;
    }
}
