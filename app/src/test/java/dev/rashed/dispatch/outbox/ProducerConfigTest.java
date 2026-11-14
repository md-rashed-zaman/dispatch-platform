package dev.rashed.dispatch.outbox;

import static org.assertj.core.api.Assertions.assertThatNoException;

import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

/**
 * Kafka checks producer settings against each other only when a producer is built, and that happens at the
 * first send, not at startup. OutboxRelayTests overrides the timeouts, so this builds one from the real
 * application.yml (no broker needed) to catch a combination Kafka rejects.
 */
class ProducerConfigTest {

    private static final String PREFIX = "spring.kafka.producer.";

    @Test
    void theConfiguredProducerSettingsAreAcceptedByKafka() {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        var properties = yaml.getObject();

        Map<String, Object> config = new HashMap<>();
        config.put("bootstrap.servers", "localhost:1");
        config.put("key.serializer", properties.getProperty(PREFIX + "key-serializer"));
        config.put("value.serializer", properties.getProperty(PREFIX + "value-serializer"));
        config.put("acks", properties.getProperty(PREFIX + "acks"));
        // Not stringPropertyNames(): YAML numbers load as Integer, and that method silently skips them.
        properties.forEach((key, value) -> {
            var name = key.toString();
            if (name.startsWith(PREFIX + "properties.")) {
                config.put(name.substring((PREFIX + "properties.").length()), value.toString());
            }
        });

        assertThatNoException().isThrownBy(() -> new KafkaProducer<>(config).close());
    }
}
