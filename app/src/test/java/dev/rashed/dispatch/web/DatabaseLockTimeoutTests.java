package dev.rashed.dispatch.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import dev.rashed.dispatch.TestTokens;
import dev.rashed.dispatch.TestcontainersConfiguration;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Chaos day (docs/runbooks/postmortem-2026-11-16.md): with a table locked by someone else, a request must give
 * up within the role's lock_timeout (V14) and answer 503, instead of hanging and holding a pooled connection.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class DatabaseLockTimeoutTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void aRequestBlockedByALockAnswers503WithinTheLockTimeout() throws Exception {
        var tenant = UUID.randomUUID();
        jdbc.update("insert into company (id, name, slug) values (?, 'Company', ?)", tenant, "company-" + tenant);
        var dispatcher =
                TestTokens.token(c -> c.claim("roles", List.of("DISPATCHER")).claim("tenant_id", tenant.toString()));

        try (var admin = dataSource.getConnection()) { // someone else's session, holding a lock
            admin.setAutoCommit(false);
            admin.createStatement().execute("lock table work_order in exclusive mode");

            var started = System.nanoTime();
            var response = mvc.perform(post("/api/work-orders")
                            .header("Authorization", "Bearer " + dispatcher)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"title": "Router down", "description": "No internet at the branch.",
                                     "siteAddress": "Gulshan", "site": {"latitude": 23.793, "longitude": 90.413},
                                     "requiredSkills": ["networking"],
                                     "timeWindow": {"startsAt": "2026-12-01T04:00:00Z", "endsAt": "2026-12-01T08:00:00Z"},
                                     "budget": {"minor": 300000, "currency": "BDT"}, "checklist": ["Photo"]}
                                    """))
                    .andReturn()
                    .getResponse();
            var waited = Duration.ofNanos(System.nanoTime() - started);

            assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(503);
            assertThat(response.getHeader("Retry-After")).isNotNull();
            assertThat(waited).isBetween(Duration.ofSeconds(4), Duration.ofSeconds(8)); // lock_timeout is 5 s
            admin.rollback();
        }
    }
}
