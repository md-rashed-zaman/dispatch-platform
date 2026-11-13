package dev.rashed.dispatch.tenancy;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.rashed.dispatch.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class RowLevelSecurityGuardTests {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PostgreSQLContainer postgres;

    @Test
    void acceptsTheAppRole() {
        assertThatCode(() -> RowLevelSecurityGuard.verify(jdbc)).doesNotThrowAnyException();
    }

    @Test
    void rejectsTheOwnerRoleWhichWouldSkipThePolicies() {
        var owner = new SingleConnectionDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), true);
        try {
            assertThatThrownBy(() -> RowLevelSecurityGuard.verify(new JdbcTemplate(owner)))
                    .hasMessageContaining("bypasses row-level security");
        } finally {
            owner.destroy();
        }
    }
}
