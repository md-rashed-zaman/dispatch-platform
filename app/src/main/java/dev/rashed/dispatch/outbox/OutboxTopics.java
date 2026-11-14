package dev.rashed.dispatch.outbox;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/** Topics Dispatch publishes to. The broker refuses to create topics on its own. */
@Configuration
class OutboxTopics {

    // Partitions bound how many consumers can share the work; one work order's events stay in one
    // partition (same key), so they keep their order.
    @Bean
    NewTopic workOrderEvents(
            @Value("${dispatch.outbox.partitions:6}") int partitions,
            @Value("${dispatch.outbox.replicas:1}") int replicas) {
        return TopicBuilder.name(Topics.WORK_ORDER_EVENTS)
                .partitions(partitions)
                .replicas(replicas)
                .build();
    }
}
