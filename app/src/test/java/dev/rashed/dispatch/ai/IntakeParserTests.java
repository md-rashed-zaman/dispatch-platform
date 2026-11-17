package dev.rashed.dispatch.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.google.genai.errors.ServerException;
import dev.rashed.dispatch.TestTokens;
import dev.rashed.dispatch.TestcontainersConfiguration;
import dev.rashed.dispatch.technician.SkillCatalog;
import dev.rashed.dispatch.tenancy.TenantContext;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
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

/**
 * The model's answer is untrusted: a valid one becomes a draft, an invalid one gets one corrected try with the
 * errors spelled out, and what still fails is dropped field by field. Same context as {@link AiGatewayTests}.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "dispatch.ai.retry-backoff=PT0.01S")
@AutoConfigureMockMvc
class IntakeParserTests {

    /** A Wednesday morning in Dhaka. */
    static final ZonedDateTime NOW = ZonedDateTime.of(2026, 11, 18, 10, 0, 0, 0, IntakeParser.DHAKA);

    static final String MESSAGE = "Gulshan office printer shows E05, need someone tomorrow morning";

    static final String VALID = """
            {"title": "Printer shows error E05", "skills": ["printer"], "urgency": "NORMAL",
             "siteAddress": "Gulshan office", "startsAt": "2026-11-19T09:00", "endsAt": "2026-11-19T12:00",
             "checklist": ["Photo of the printer status screen"]}
            """;

    @MockitoBean
    ChatModel model;

    @MockitoBean
    SkillCatalog catalog;

    @Autowired
    IntakeParser intake;

    @Autowired
    CircuitBreakerRegistry breakers;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    final UUID tenant = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        jdbc.update("insert into company (id, name, slug) values (?, 'Company', ?)", tenant, "company-" + tenant);
        given(catalog.codes()).willReturn(new TreeSet<>(List.of("cctv", "networking", "printer", "pos-terminal")));
    }

    @AfterEach
    void resetBreakers() {
        breakers.getAllCircuitBreakers().forEach(breaker -> breaker.reset());
    }

    @Test
    void aValidAnswerBecomesACompleteDraft() {
        given(model.call(any(Prompt.class))).willReturn(reply(VALID));

        var result = draft(MESSAGE);

        assertThat(result.available()).isTrue();
        assertThat(result.missing()).containsExactly("siteLocation"); // coordinates are never guessed
        var draft = result.draft();
        assertThat(draft.title()).isEqualTo("Printer shows error E05");
        assertThat(draft.description()).isEqualTo(MESSAGE);
        assertThat(draft.skills()).containsExactly("printer");
        assertThat(draft.siteAddress()).isEqualTo("Gulshan office");
        assertThat(draft.timeWindow().startsAt()).isEqualTo(Instant.parse("2026-11-19T03:00:00Z")); // 09:00 Dhaka
        assertThat(draft.timeWindow().endsAt()).isEqualTo(Instant.parse("2026-11-19T06:00:00Z"));

        var sent = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(sent.capture());
        assertThat(sent.getValue().getContents())
                .contains(MESSAGE, "2026-11-18T10:00, Wednesday", "cctv, networking, pos-terminal, printer")
                .contains("Corrections to your previous answer, if any: none");
    }

    @Test
    void anInvalidAnswerGetsOneCorrectedTryWithTheErrors() {
        given(model.call(any(Prompt.class)))
                .willReturn(reply(VALID.replace("[\"printer\"]", "[\"toner-refill\"]")
                        .replace("\"endsAt\": \"2026-11-19T12:00\"", "\"endsAt\": \"2026-11-19T08:00\"")))
                .willReturn(reply(VALID));

        var result = draft(MESSAGE);

        assertThat(result.missing()).containsExactly("siteLocation");
        assertThat(result.draft().skills()).containsExactly("printer");
        var sent = ArgumentCaptor.forClass(Prompt.class);
        verify(model, times(2)).call(sent.capture());
        assertThat(sent.getAllValues().get(1).getContents())
                .contains("'toner-refill' is not on the list", "endsAt: must be after startsAt")
                .contains("Your previous answer was");
    }

    @Test
    void whatStaysInvalidIsDroppedAndNamedAsMissing() {
        var wrong = VALID.replace("[\"printer\"]", "[\"printer\", \"toner-refill\"]")
                .replace("2026-11-19T09:00", "2026-11-17T09:00") // yesterday
                .replace("2026-11-19T12:00", "2026-11-17T12:00");
        given(model.call(any(Prompt.class))).willReturn(reply(wrong));

        var result = draft(MESSAGE);

        verify(model, times(2)).call(any(Prompt.class)); // one correction, not a loop
        assertThat(result.available()).isTrue();
        assertThat(result.draft().title()).isEqualTo("Printer shows error E05"); // the valid parts stay
        assertThat(result.draft().skills()).containsExactly("printer");
        assertThat(result.draft().timeWindow()).isNull();
        assertThat(result.missing()).containsExactly("timeWindow", "siteLocation");
    }

    @Test
    void anAnswerThatIsntJsonLeavesTheFormToThePerson() {
        given(model.call(any(Prompt.class))).willReturn(reply("I think you need a printer technician tomorrow."));

        var result = draft(MESSAGE);

        assertThat(result.available()).isFalse();
        assertThat(result.draft().description()).isEqualTo(MESSAGE); // their words aren't lost
        assertThat(result.missing()).contains("title", "skills", "timeWindow", "siteLocation");
    }

    @Test
    void theEndpointAnswersEvenWhenAiIsDown() throws Exception {
        given(model.call(any(Prompt.class))).willThrow(new ServerException(503, "UNAVAILABLE", "high demand"));
        var dispatcher =
                TestTokens.token(c -> c.claim("roles", List.of("DISPATCHER")).claim("tenant_id", tenant.toString()));

        mvc.perform(post("/api/work-orders/draft-from-text")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + dispatcher)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\": \"" + MESSAGE + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.draft.description").value(MESSAGE));

        mvc.perform(post("/api/work-orders/draft-from-text")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + dispatcher)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\": \" \"}"))
                .andExpect(status().isBadRequest());

        var technician = TestTokens.token(c -> c.claim("roles", List.of("TECHNICIAN"))
                .claim("technician_id", UUID.randomUUID().toString()));
        mvc.perform(post("/api/work-orders/draft-from-text")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + technician)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\": \"" + MESSAGE + "\"}"))
                .andExpect(status().isForbidden());
    }

    private IntakeParser.Result draft(String text) {
        return TenantContext.callAs(tenant, () -> intake.draft(text, NOW));
    }

    private static ChatResponse reply(String text) {
        return new ChatResponse(
                List.of(new Generation(new AssistantMessage(text))),
                ChatResponseMetadata.builder().usage(new DefaultUsage(400, 60)).build());
    }
}
