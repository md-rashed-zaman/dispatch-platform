package dev.rashed.dispatch;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.kafka.KafkaContainer;

/** A real Kafka (same image as infra/compose), for the tests that check events leave the service. */
@TestConfiguration(proxyBeanMethods = false)
public class KafkaTestcontainersConfiguration {

    @Bean
    KafkaContainer kafkaContainer() {
        // Kafka's default 60 s to log "RUNNING" isn't enough on a loaded machine (a whole suite of contexts, swap):
        // the step 4.5 run timed out here once. Startup is normally ~15 s, so 3 minutes only matters when it's slow.
        return new KafkaContainer("apache/kafka:4.3.2").withStartupTimeout(java.time.Duration.ofMinutes(3));
    }

    @Bean
    DynamicPropertyRegistrar kafkaProperties(KafkaContainer kafkaContainer) {
        return registry -> {
            registry.add("spring.kafka.bootstrap-servers", kafkaContainer::getBootstrapServers);
            registry.add("dispatch.outbox.relay-enabled", () -> "true");
            registry.add("dispatch.notification.consumer-enabled", () -> "true");
            registry.add("dispatch.notification.retry.initial-interval", () -> "PT0.1S");
            registry.add("spring.kafka.admin.auto-create", () -> "true");
            registry.add("dispatch.outbox.relay-interval", () -> "PT0.2S");
            // Give up on an unreachable broker within seconds, so the outage test stays short.
            registry.add("spring.kafka.producer.properties.max.block.ms", () -> "2000");
            registry.add("spring.kafka.producer.properties.request.timeout.ms", () -> "2000");
            registry.add("spring.kafka.producer.properties.delivery.timeout.ms", () -> "4000");
            registry.add("dispatch.outbox.send-timeout", () -> "PT5S");
        };
    }
}
