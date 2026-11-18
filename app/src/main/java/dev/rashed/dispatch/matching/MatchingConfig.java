package dev.rashed.dispatch.matching;

import dev.rashed.dispatch.outbox.DeadLetterListeners;
import dev.rashed.dispatch.outbox.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;

@Configuration
@EnableConfigurationProperties(MatchingProperties.class)
class MatchingConfig {

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

    /** The embedder's own factory, so its failures go to its own dead-letter topic. */
    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> embedderListenerFactory(
            ConsumerFactory<String, String> consumers, KafkaTemplate<String, String> kafka) {
        return DeadLetterListeners.factory(consumers, kafka, EMBEDDER_DEAD_LETTERS);
    }
}
