package dev.rashed.dispatch.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.google.genai.errors.ClientException;
import com.google.genai.errors.GenAiIOException;
import com.google.genai.errors.ServerException;
import dev.rashed.dispatch.TestTokens;
import dev.rashed.dispatch.TestcontainersConfiguration;
import dev.rashed.dispatch.technician.SkillCatalog;
import dev.rashed.dispatch.tenancy.TenantContext;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The gateway against a scripted model: every failure the free tier showed in step 3.1 (503, hangs, a bad key,
 * an answer that isn't JSON) ends in either an answer or the feature's fallback, never an error for the user;
 * and every attempt leaves an audit row.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "dispatch.ai.retry-backoff=PT0.01S")
@AutoConfigureMockMvc
class AiGatewayTests {

    static final String LITE = "gemini-flash-lite-latest";
    static final String SECOND = "gemini-3.1-flash-lite";

    @MockitoBean
    ChatModel model;

    @MockitoBean
    SkillCatalog catalog;

    @Autowired
    SkillSuggestions suggestions;

    @Autowired
    AiCallLog calls;

    @Autowired
    CircuitBreakerRegistry breakers;

    @Autowired
    RateLimiterRegistry rateLimiters;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate transaction;

    final UUID tenant = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        jdbc.update("insert into company (id, name, slug) values (?, 'Company', ?)", tenant, "company-" + tenant);
        given(catalog.codes()).willReturn(new TreeSet<>(List.of("cctv", "networking", "printer", "pos-terminal")));
    }

    @AfterEach
    void resetResilience() {
        breakers.getAllCircuitBreakers().forEach(breaker -> breaker.reset());
        var configured = rateLimiters.rateLimiter(SECOND).getRateLimiterConfig(); // same settings for both
        rateLimiters.replace(LITE, RateLimiter.of(LITE, configured));
    }

    @Test
    void keepsOnlySkillsTechniciansHaveAndRecordsTheCall() {
        given(model.call(any(Prompt.class)))
                .willReturn(reply("{\"skills\": [\"printer\", \"toner-refill\", \"printer\"]}"));

        var suggestion =
                ask("Printer E05", "Gulshan office printer shows E05. Call Karim on 01712-345678 or karim@bank.com");

        assertThat(suggestion.available()).isTrue();
        assertThat(suggestion.skills()).containsExactly("printer"); // the invented code and the duplicate are gone

        var sent = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(sent.capture());
        assertThat(sent.getValue().getContents())
                .contains("Gulshan office printer shows E05", "cctv, networking, pos-terminal, printer")
                .doesNotContain("01712-345678", "karim@bank.com")
                .contains("[phone]", "[email]");

        var rows = rows();
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst())
                .containsEntry("model", LITE)
                .containsEntry("outcome", "OK")
                .containsEntry("prompt_version", "v1")
                .containsEntry("input_tokens", 120)
                .containsEntry("output_tokens", 8);
        assertThat((String) rows.getFirst().get("input_redacted"))
                .contains("[phone]")
                .doesNotContain("01712");
    }

    @Test
    void retriesA503OnceThenAnswers() {
        given(model.call(any(Prompt.class)))
                .willThrow(new ServerException(503, "UNAVAILABLE", "This model is currently experiencing high demand"))
                .willReturn(reply("{\"skills\": [\"cctv\"]}"));

        assertThat(ask("Camera offline", "Vault camera 3 shows no signal").skills())
                .containsExactly("cctv");
        assertThat(outcomes()).containsExactly(LITE + " ERROR", LITE + " OK");
    }

    @Test
    void movesToTheNextModelWhenOneKeepsFailing() {
        given(model.call(any(Prompt.class)))
                .willThrow(new ServerException(503, "UNAVAILABLE", "high demand"))
                .willThrow(new GenAiIOException("timeout", new SocketTimeoutException("Read timed out")))
                .willReturn(reply("{\"skills\": [\"networking\"]}"));

        assertThat(ask("Router down", "No internet at the branch").skills()).containsExactly("networking");
        assertThat(outcomes()).containsExactly(LITE + " ERROR", LITE + " ERROR", SECOND + " OK");
    }

    @Test
    void aTimeoutGoesStraightToTheNextModel() {
        given(model.call(any(Prompt.class)))
                .willThrow(new GenAiIOException("timeout", new SocketTimeoutException("Read timed out")))
                .willReturn(reply("{\"skills\": [\"cctv\"]}"));

        assertThat(ask("Camera offline", "No signal").skills()).containsExactly("cctv");
        assertThat(outcomes()).containsExactly(LITE + " ERROR", SECOND + " OK"); // no second wait on LITE
    }

    @Test
    void aBadKeyFallsBackAtOnceWithoutRetrying() {
        given(model.call(any(Prompt.class)))
                .willThrow(new ClientException(
                        400, "INVALID_ARGUMENT", "API key not valid. Please pass a valid API key."));

        var suggestion = ask("Router down", "No internet at the branch");

        assertThat(suggestion.available()).isFalse();
        assertThat(suggestion.skills()).isEmpty();
        verify(model, times(1)).call(any(Prompt.class));
        assertThat(rows().getFirst().get("error").toString()).startsWith("400 INVALID_ARGUMENT");
    }

    @Test
    void anAnswerThatIsntTheRightShapeIsNotUsed() {
        given(model.call(any(Prompt.class))).willReturn(reply("Sure! The technician needs networking skills."));

        var suggestion = ask("Router down", "No internet at the branch");

        assertThat(suggestion.available()).isFalse();
        assertThat(outcomes()).containsExactly(LITE + " INVALID_OUTPUT", SECOND + " INVALID_OUTPUT");
    }

    @Test
    void anEmptyAnswerIsNotACrash() {
        // What a safety block looks like: no candidates at all.
        given(model.call(any(Prompt.class))).willReturn(new ChatResponse(List.of()));

        assertThat(ask("Router down", "No internet at the branch").available()).isFalse();
    }

    @Test
    void anOpenCircuitSkipsTheModelWithoutCallingIt() {
        breakers.circuitBreaker(LITE).transitionToOpenState();
        given(model.call(any(Prompt.class))).willReturn(reply("{\"skills\": [\"cctv\"]}"));

        assertThat(ask("Camera offline", "No signal").skills()).containsExactly("cctv");
        assertThat(outcomes()).containsExactly(LITE + " CIRCUIT_OPEN", SECOND + " OK");
        verify(model, times(1)).call(any(Prompt.class));
    }

    @Test
    void theCircuitOpensAfterRepeatedFailures() {
        given(model.call(any(Prompt.class))).willThrow(new ServerException(503, "UNAVAILABLE", "high demand"));

        ask("a", "b"); // 2 failures on each model
        ask("a", "b"); // 4 on each: both breakers open (half of at least 4 failed)
        clearInvocations(model);

        assertThat(ask("a", "b").available()).isFalse();
        verify(model, never()).call(any(Prompt.class)); // failing fast, not waiting for timeouts
    }

    @Test
    void overTheClientSideRateLimitGoesToTheNextModel() {
        var configured = rateLimiters.rateLimiter(LITE).getRateLimiterConfig();
        var exhausted = RateLimiter.of(
                LITE, RateLimiterConfig.from(configured).limitForPeriod(1).build());
        exhausted.drainPermissions(); // this minute's requests are used up
        rateLimiters.replace(LITE, exhausted);
        given(model.call(any(Prompt.class))).willReturn(reply("{\"skills\": [\"cctv\"]}"));

        assertThat(ask("Camera offline", "No signal").skills()).containsExactly("cctv");
        assertThat(outcomes()).containsExactly(LITE + " RATE_LIMITED", SECOND + " OK");
    }

    @Test
    void aCompanyOverItsDailyBudgetGetsTheFallbackWithoutACall() {
        TenantContext.runAs(tenant, () -> {
            for (int i = 0; i < 100; i++) {
                calls.record(new AiCallLog.Entry(
                        tenant, "skill-suggestions", "v1", LITE, AiCallLog.Outcome.OK, "{}", "{}", null, 1, 1, 1));
            }
        });

        assertThat(ask("Camera offline", "No signal").available()).isFalse();
        verify(model, never()).call(any(Prompt.class));
        assertThat(outcomes()).last().isEqualTo("- BUDGET_EXCEEDED");
    }

    @Test
    void theEndpointAnswersEvenWhenAiIsDown() throws Exception {
        given(model.call(any(Prompt.class))).willThrow(new ServerException(503, "UNAVAILABLE", "high demand"));
        var dispatcher =
                TestTokens.token(c -> c.claim("roles", List.of("DISPATCHER")).claim("tenant_id", tenant.toString()));

        mvc.perform(post("/api/ai/skill-suggestions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + dispatcher)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \"Camera offline\", \"description\": \"No signal\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.skills").isEmpty());
    }

    @Test
    void techniciansCantAskAndEmptyTextIsRejected() throws Exception {
        var technician = TestTokens.token(c -> c.claim("roles", List.of("TECHNICIAN"))
                .claim("technician_id", UUID.randomUUID().toString()));
        mvc.perform(post("/api/ai/skill-suggestions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + technician)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \"Camera offline\", \"description\": \"No signal\"}"))
                .andExpect(status().isForbidden());

        var dispatcher =
                TestTokens.token(c -> c.claim("roles", List.of("DISPATCHER")).claim("tenant_id", tenant.toString()));
        mvc.perform(post("/api/ai/skill-suggestions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + dispatcher)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \"\", \"description\": \"No signal\"}"))
                .andExpect(status().isBadRequest());
        verify(model, never()).call(any(Prompt.class));
    }

    @Test
    void anotherCompanyCantSeeTheseCalls() {
        given(model.call(any(Prompt.class))).willReturn(reply("{\"skills\": [\"cctv\"]}"));
        ask("Camera offline", "No signal");

        var other = UUID.randomUUID();
        jdbc.update("insert into company (id, name, slug) values (?, 'Other', ?)", other, "company-" + other);
        Integer visible = TenantContext.callAs(
                other,
                () -> transaction.execute(
                        status -> jdbc.queryForObject("select count(*) from ai_call", Integer.class)));
        assertThat(visible).isZero();
    }

    private SkillSuggestions.Suggestion ask(String title, String description) {
        var dispatcher = new org.springframework.security.authentication.TestingAuthenticationToken(
                "dispatcher", null, "ROLE_DISPATCHER");
        var context = org.springframework.security.core.context.SecurityContextHolder.getContext();
        var previous = context.getAuthentication();
        context.setAuthentication(dispatcher);
        try {
            return TenantContext.callAs(tenant, () -> suggestions.suggest(title, description));
        } finally {
            context.setAuthentication(previous);
        }
    }

    private List<Map<String, Object>> rows() {
        return TenantContext.callAs(
                tenant,
                () -> transaction.execute(
                        status -> jdbc.queryForList("select * from ai_call where tenant_id = ? order by id", tenant)));
    }

    private List<String> outcomes() {
        return rows().stream()
                .map(row -> row.get("model") + " " + row.get("outcome"))
                .toList();
    }

    private static ChatResponse reply(String text) {
        return new ChatResponse(
                List.of(new Generation(new AssistantMessage(text))),
                ChatResponseMetadata.builder().usage(new DefaultUsage(120, 8)).build());
    }
}
