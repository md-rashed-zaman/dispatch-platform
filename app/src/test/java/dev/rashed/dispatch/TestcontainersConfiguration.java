package dev.rashed.dispatch;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    // Same image as local dev (built from infra/compose/postgres), so tests get PostGIS and pgvector.
    @Bean
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(
                DockerImageName.parse("dispatch-postgres:local").asCompatibleSubstituteFor("postgres"));
    }

    // Object storage with the S3 API, same image and credentials as infra/compose.
    @Bean
    GenericContainer<?> seaweedfsContainer() {
        return new GenericContainer<>(DockerImageName.parse("chrislusf/seaweedfs:4.51"))
                .withCopyFileToContainer(
                        MountableFile.forHostPath("../infra/compose/seaweedfs/s3.json"), "/etc/seaweedfs/s3.json")
                .withCommand("server", "-dir=/data", "-s3", "-s3.port=8333", "-s3.config=/etc/seaweedfs/s3.json")
                .withExposedPorts(8333)
                .waitingFor(Wait.forHttp("/").forPort(8333).forStatusCode(403)); // up, and refusing anonymous calls
    }

    // Like production: Flyway migrates as the container's superuser, while the app and the tests run as
    // dispatch_app (credentials from application.yml), so row-level security really applies in tests.
    @Bean
    DynamicPropertyRegistrar containerProperties(PostgreSQLContainer postgres, GenericContainer<?> seaweedfsContainer) {
        return registry -> {
            registry.add(
                    "dispatch.storage.endpoint",
                    () -> "http://" + seaweedfsContainer.getHost() + ":" + seaweedfsContainer.getMappedPort(8333));
            registry.add("spring.datasource.url", postgres::getJdbcUrl);
            registry.add("spring.flyway.user", postgres::getUsername);
            registry.add("spring.flyway.password", postgres::getPassword);
        };
    }
}
