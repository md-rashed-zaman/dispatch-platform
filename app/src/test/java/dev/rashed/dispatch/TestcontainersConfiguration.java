package dev.rashed.dispatch;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * One PostgreSQL and one SeaweedFS for the whole test run; each Spring test context gets its own empty database and
 * its own bucket in them, so contexts stay as isolated as with containers of their own.
 *
 * <p>Why shared: every cached context used to start (and, at the end, stop) its own pair. With 15+ contexts the
 * JVM spent its shutdown stopping 30 containers one by one, and Surefire killed it after 30 s. Containers that
 * aren't Spring beans aren't stopped by Spring; Testcontainers' Ryuk removes them when the JVM exits.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    // Same image as local dev (built from infra/compose/postgres), so tests get PostGIS and pgvector. Enough
    // connections for every cached context's pool at once (10 each).
    public static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
                    DockerImageName.parse("dispatch-postgres:local").asCompatibleSubstituteFor("postgres"))
            .withCommand("postgres", "-c", "fsync=off", "-c", "max_connections=500");

    // Object storage with the S3 API, same image and credentials as infra/compose.
    public static final GenericContainer<?> SEAWEEDFS = new GenericContainer<>(
                    DockerImageName.parse("chrislusf/seaweedfs:4.51"))
            .withCopyFileToContainer(
                    MountableFile.forHostPath("../infra/compose/seaweedfs/s3.json"), "/etc/seaweedfs/s3.json")
            .withCommand("server", "-dir=/data", "-s3", "-s3.port=8333", "-s3.config=/etc/seaweedfs/s3.json")
            .withExposedPorts(8333)
            .waitingFor(Wait.forHttp("/").forPort(8333).forStatusCode(403)); // up, and refusing anonymous calls

    private static final AtomicInteger CONTEXTS = new AtomicInteger();

    static {
        Startables.deepStart(POSTGRES, SEAWEEDFS).join();
    }

    // Like production: Flyway migrates as the container's superuser, while the app and the tests run as
    // dispatch_app (credentials from application.yml), so row-level security really applies in tests.
    @Bean
    DynamicPropertyRegistrar containerProperties() {
        int context = CONTEXTS.incrementAndGet();
        var database = "dispatch_test_" + context;
        createDatabase(database);
        return registry -> {
            registry.add(
                    "dispatch.storage.endpoint",
                    () -> "http://" + SEAWEEDFS.getHost() + ":" + SEAWEEDFS.getMappedPort(8333));
            registry.add("dispatch.storage.bucket", () -> "dispatch-media-" + context); // created on startup
            registry.add("spring.datasource.url", () -> jdbcUrl(database));
            registry.add("spring.flyway.user", POSTGRES::getUsername);
            registry.add("spring.flyway.password", POSTGRES::getPassword);
        };
    }

    private static String jdbcUrl(String database) {
        return POSTGRES.getJdbcUrl().replaceFirst("/" + POSTGRES.getDatabaseName() + "(\\?|$)", "/" + database + "$1");
    }

    private static void createDatabase(String database) {
        try (var connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
        } catch (SQLException e) {
            throw new IllegalStateException("Couldn't create test database " + database, e);
        }
    }
}
