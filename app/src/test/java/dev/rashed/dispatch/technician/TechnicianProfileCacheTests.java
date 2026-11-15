package dev.rashed.dispatch.technician;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.jayway.jsonpath.JsonPath;
import dev.rashed.dispatch.RedisTestcontainersConfiguration;
import dev.rashed.dispatch.TestTechnicians;
import dev.rashed.dispatch.TestTokens;
import dev.rashed.dispatch.TestcontainersConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.GenericContainer;

/** Technician profiles come from Redis after the first read, and an update is visible on the very next read. */
@Import({TestcontainersConfiguration.class, RedisTestcontainersConfiguration.class})
@SpringBootTest(properties = "spring.cache.type=redis")
@AutoConfigureMockMvc
class TechnicianProfileCacheTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    MeterRegistry meters;

    @Autowired
    GenericContainer<?> redisContainer;

    UUID technician;
    final String dispatcher = TestTokens.dispatcher();

    @BeforeEach
    void setUp() {
        technician = TestTechnicians.verified(jdbc, 23.79, 90.41, "networking");
    }

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
    void theSecondReadIsServedFromTheCache() throws Exception {
        var hitsBefore = hits();

        assertThat(call(get("/api/technicians/{id}", technician), dispatcher).getStatus())
                .isEqualTo(200);
        assertThat(redis.hasKey(key())).isTrue();
        jdbc.update("update technician set full_name = 'Changed Behind Our Back' where id = ?", technician);
        var second = call(get("/api/technicians/{id}", technician), dispatcher);

        // The database changed without going through the app, so only a cached copy explains the old name.
        assertThat((String) JsonPath.read(second.getContentAsString(), "$.fullName"))
                .isEqualTo("Test Technician");
        assertThat(hits()).isEqualTo(hitsBefore + 1);
        assertThat(second.getContentAsString()).doesNotContain("phone").doesNotContain("home");
        assertThat(redis.getExpire(key()))
                .isBetween(
                        Duration.ofMinutes(9).toSeconds(),
                        Duration.ofMinutes(11).toSeconds());
    }

    @Test
    void anUpdateIsVisibleOnTheVeryNextRead() throws Exception {
        call(get("/api/technicians/{id}", technician), dispatcher); // cached with networking

        var update = call(
                put("/api/technicians/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"skills\": [\"cctv\", \"networking\"], \"serviceRadiusKm\": 45}"),
                TestTechnicians.token(technician));
        assertThat(update.getStatus()).as(update.getContentAsString()).isEqualTo(200);
        var after = call(get("/api/technicians/{id}", technician), dispatcher);

        assertThat((List<String>) JsonPath.read(after.getContentAsString(), "$.skills"))
                .containsExactly("cctv", "networking");
        assertThat((Integer) JsonPath.read(after.getContentAsString(), "$.serviceRadiusKm"))
                .isEqualTo(45);
    }

    @Test
    void onlyATechnicianCanUpdateAProfile() throws Exception {
        var response = call(
                put("/api/technicians/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"skills\": [\"cctv\"], \"serviceRadiusKm\": 10}"),
                dispatcher);

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void withRedisDownProfilesComeFromTheDatabase() throws Exception {
        redisContainer
                .getDockerClient()
                .pauseContainerCmd(redisContainer.getContainerId())
                .exec();

        var started = System.nanoTime();
        var response = call(get("/api/technicians/{id}", technician), dispatcher);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat((String) JsonPath.read(response.getContentAsString(), "$.fullName"))
                .isEqualTo("Test Technician");
        // Two cache calls (get, then put) each wait at most the Redis timeout, plus the rate limiter's.
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
    }

    private String key() {
        return TechnicianProfiles.CACHE + "::" + technician;
    }

    private double hits() {
        var counter = meters.find("cache.gets")
                .tags("cache", TechnicianProfiles.CACHE, "result", "hit")
                .functionCounter();
        return counter == null ? 0 : counter.count();
    }

    private MockHttpServletResponse call(MockHttpServletRequestBuilder request, String token) throws Exception {
        return mvc.perform(request.header("Authorization", "Bearer " + token))
                .andReturn()
                .getResponse();
    }
}
