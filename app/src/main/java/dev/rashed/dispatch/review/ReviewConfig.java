package dev.rashed.dispatch.review;

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

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ReviewProperties.class)
class ReviewConfig {

    static final String AUDITOR_DEAD_LETTERS = Topics.WORK_ORDER_EVENTS + "." + CompletionAuditor.CONSUMER + "-dlt";

    @Bean
    NewTopic auditorDeadLetters(
            @Value("${dispatch.outbox.partitions:6}") int partitions,
            @Value("${dispatch.outbox.replicas:1}") int replicas) {
        return TopicBuilder.name(AUDITOR_DEAD_LETTERS)
                .partitions(partitions)
                .replicas(replicas)
                .build();
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> auditorListenerFactory(
            ConsumerFactory<String, String> consumers, KafkaTemplate<String, String> kafka) {
        return DeadLetterListeners.factory(consumers, kafka, AUDITOR_DEAD_LETTERS);
    }
}
