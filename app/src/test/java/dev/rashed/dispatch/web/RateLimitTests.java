package dev.rashed.dispatch.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import dev.rashed.dispatch.RedisTestcontainersConfiguration;
import dev.rashed.dispatch.TestTokens;
import dev.rashed.dispatch.TestcontainersConfiguration;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;

/** Each company gets a burst of 100 requests, then 429 with Retry-After; others aren't affected. */
@Import({TestcontainersConfiguration.class, RedisTestcontainersConfiguration.class})
@SpringBootTest(properties = "spring.cache.type=redis")
@AutoConfigureMockMvc
class RateLimitTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    GenericContainer<?> redisContainer;

    @AfterEach
    void redisBack() {
        var docker = redisContainer.getDockerClient();
        if (docker.inspectContainerCmd(redisContainer.getContainerId())
                .exec()
                .getState()
                .getPaused()) {
            docker.unpauseContainerCmd(redisContainer.getContainerId()).exec();
            // Until the rate limiter uses Redis again (its circuit breaker tries again after a second), so the
            // next test isn't let through by a breaker this test opened.
            await().atMost(Duration.ofSeconds(15))
                    .until(() ->
                            mvc.perform(get("/api/me").header("Authorization", "Bearer " + TestTokens.dispatcher()))
                                            .andReturn()
                                            .getResponse()
                                            .getHeader("X-RateLimit-Remaining")
                                    != null);
        }
    }

    @Test
    void aBurstOf200GetsTheFirst100ThroughAndThen429WithRetryAfter() throws Exception {
        var company = dispatcherOfNewCompany();

        var responses = burst(company, 200);

        var ok = responses.stream().filter(r -> r.getStatus() == 200).count();
        // 100 at once, plus the few tokens that refill (100 a minute) while the burst runs.
        assertThat(ok).isBetween(100L, 110L);
        var limited = responses.stream().filter(r -> r.getStatus() == 429).toList();
        assertThat(limited).hasSize(200 - (int) ok);
        var first = limited.getFirst();
        assertThat(Integer.parseInt(first.getHeader("Retry-After"))).isBetween(1, 60);
        assertThat(first.getHeader("X-RateLimit-Remaining")).isEqualTo("0");
        assertThat(first.getContentType()).isEqualTo("application/problem+json");
        assertThat(first.getContentAsString()).contains("urn:dispatch:problem:rate-limited");
    }

    @Test
    void oneCompanyUsingUpItsLimitDoesntAffectAnother() throws Exception {
        var noisy = dispatcherOfNewCompany();
        var quiet = dispatcherOfNewCompany();
        burst(noisy, 150);

        assertThat(burst(quiet, 1).getFirst().getStatus()).isEqualTo(200);
    }

    @Test
    void withRedisDownRequestsAreLetThrough() throws Exception {
        var company = dispatcherOfNewCompany();
        redisContainer
                .getDockerClient()
                .pauseContainerCmd(redisContainer.getContainerId())
                .exec();

        var responses = burst(company, 150);

        assertThat(responses).allSatisfy(r -> assertThat(r.getStatus()).isEqualTo(200));
    }

    private String dispatcherOfNewCompany() {
        var tenant = UUID.randomUUID();
        jdbc.update("insert into company (id, name, slug) values (?, 'Company', ?)", tenant, "company-" + tenant);
        return TestTokens.token(c -> c.claim("roles", List.of("DISPATCHER")).claim("tenant_id", tenant.toString()));
    }

    private List<MockHttpServletResponse> burst(String token, int count) throws Exception {
        var responses = new ArrayList<MockHttpServletResponse>();
        for (int i = 0; i < count; i++) {
            responses.add(mvc.perform(get("/api/work-orders").header("Authorization", "Bearer " + token))
                    .andReturn()
                    .getResponse());
        }
        return responses;
    }
}
