package dev.rashed.dispatch.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration;
import org.springframework.ai.model.google.genai.autoconfigure.chat.GoogleGenAiChatAutoConfiguration;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.retry.autoconfigure.SpringAiRetryAutoConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;

/**
 * One real Gemini call through the same Spring AI auto-configuration the app uses (step 3.1). It needs a key, so
 * it runs only when GEMINI_API_KEY is exported ({@code export $(grep ^GEMINI_API_KEY= ../.env)}): CI has none
 * and never calls the API.
 */
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
@SpringBootTest(classes = GeminiChatClientTests.GeminiOnly.class)
class GeminiChatClientTests {

    @Configuration
    @ImportAutoConfiguration({
        GoogleGenAiChatAutoConfiguration.class,
        ChatClientAutoConfiguration.class,
        ToolCallingAutoConfiguration.class,
        SpringAiRetryAutoConfiguration.class
    })
    static class GeminiOnly {}

    record Intake(String title, List<String> skills, String area) {}

    @Autowired
    ChatClient.Builder chatClient;

    @Test
    void answersAQuestionAndReportsTheTokensItUsed() {
        long started = System.nanoTime();
        ChatResponse response = chatClient
                .build()
                .prompt()
                .system("You are a field service dispatcher. Answer in one sentence.")
                .user("Gulshan office printer shows E05, need someone tomorrow morning. Which skill is needed?")
                .call()
                .chatResponse();
        long millis = (System.nanoTime() - started) / 1_000_000;

        Usage usage = response.getMetadata().getUsage();
        System.out.printf(
                "model=%s latency=%d ms prompt=%d completion=%d tokens%n",
                response.getMetadata().getModel(), millis, usage.getPromptTokens(), usage.getCompletionTokens());
        assertThat(response.getResult().getOutput().getText()).containsIgnoringCase("print");
        assertThat(usage.getPromptTokens()).isPositive();
    }

    @Test
    void returnsStructuredOutputAsARecord() {
        // Spring AI adds the JSON schema of Intake to the prompt and parses the reply. The model can still
        // return something invalid, so real features validate it like any untrusted input (step 3.3).
        Intake intake = chatClient
                .build()
                .prompt()
                .user("Turn this into a work order. Skills are lowercase-with-dashes. "
                        + "Text: Gulshan office printer shows E05, need someone tomorrow morning")
                .call()
                .entity(Intake.class);

        System.out.println(intake);
        assertThat(intake.skills()).isNotEmpty().allMatch(s -> s.matches("[a-z0-9-]+"));
        assertThat(intake.area()).containsIgnoringCase("gulshan");
    }
}
