package dev.rashed.dispatch;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    // Same image as local dev (built from infra/compose/postgres), so tests get PostGIS and pgvector.
    @Bean
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(
                DockerImageName.parse("dispatch-postgres:local").asCompatibleSubstituteFor("postgres"));
    }

    // Like production: Flyway migrates as the container's superuser, while the app and the tests run as
    // dispatch_app (credentials from application.yml), so row-level security really applies in tests.
    @Bean
    DynamicPropertyRegistrar databaseProperties(PostgreSQLContainer postgres) {
        return registry -> {
            registry.add("spring.datasource.url", postgres::getJdbcUrl);
            registry.add("spring.flyway.user", postgres::getUsername);
            registry.add("spring.flyway.password", postgres::getPassword);
        };
    }
}
