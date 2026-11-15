package dev.rashed.dispatch;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;

/**
 * A real Redis (same image as infra/compose), for the cache and rate-limit tests. Tests using it also set
 * {@code spring.cache.type=redis} in {@code @SpringBootTest}: the cache type is a startup condition, decided
 * before these dynamic properties exist.
 */
@TestConfiguration(proxyBeanMethods = false)
public class RedisTestcontainersConfiguration {

    @Bean
    GenericContainer<?> redisContainer() {
        return new GenericContainer<>("redis:8.8.4-alpine").withExposedPorts(6379);
    }

    @Bean
    DynamicPropertyRegistrar redisProperties(GenericContainer<?> redisContainer) {
        return registry -> {
            registry.add("spring.data.redis.host", redisContainer::getHost);
            registry.add("spring.data.redis.port", () -> redisContainer.getMappedPort(6379));
            registry.add("dispatch.rate-limit.enabled", () -> "true");
        };
    }
}
