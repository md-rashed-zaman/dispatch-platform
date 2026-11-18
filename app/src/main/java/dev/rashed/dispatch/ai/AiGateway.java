package dev.rashed.dispatch.ai;

import dev.rashed.dispatch.tenancy.TenantContext;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.content.Media;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeType;
import tools.jackson.databind.json.JsonMapper;

/**
 * The only way the app calls a model (ADR 0012, T28). Features ask for a typed answer; the gateway picks the
 * model, renders the versioned prompt, enforces budgets and limits, retries what's worth retrying, falls back
 * to the next model, validates the answer's shape, and records every attempt. When nothing works it says so,
 * and the feature uses its non-AI path: an AI feature is never the reason a request fails.
 *
 * <p>Called outside any transaction: a model call takes seconds and must not hold a database connection.
 */
@Component
class AiGateway {

    private static final Logger log = LoggerFactory.getLogger(AiGateway.class);
    private static final double TEMPERATURE = 0.2; // extraction and classification, not creativity (T27)

    private final ChatModel chatModel;
    private final AiProperties ai;
    private final AiCallLog calls;
    private final ReplayCache replays;
    private final AiMetrics metrics;
    private final CircuitBreakerRegistry breakers;
    private final RateLimiterRegistry rateLimiters;
    private final ResourceLoader resources;
    private final JsonMapper json;

    AiGateway(
            ChatModel chatModel,
            AiProperties ai,
            AiCallLog calls,
            ReplayCache replays,
            AiMetrics metrics,
            CircuitBreakerRegistry aiCircuitBreakers,
            RateLimiterRegistry aiRateLimiters,
            ResourceLoader resources,
            JsonMapper json) {
        this.chatModel = chatModel;
        this.ai = ai;
        this.calls = calls;
        this.replays = replays;
        this.metrics = metrics;
        this.breakers = aiCircuitBreakers;
        this.rateLimiters = aiRateLimiters;
        this.resources = resources;
        this.json = json;
    }

    /**
     * What a feature asks for.
     *
     * @param feature also the prompt folder: {@code prompts/<feature>/<promptVersion>.st}
     * @param input the user's text and other values for the template; strings are redacted before use
     */
    record Task<T>(
            String feature, String promptVersion, Map<String, Object> input, Class<T> answerType, List<Image> images) {

        Task(String feature, String promptVersion, Map<String, Object> input, Class<T> answerType) {
            this(feature, promptVersion, input, answerType, List.of());
        }
    }

    /** A photo sent with the prompt (vision models). Its hash is part of the replay key, never the bytes in logs. */
    record Image(String contentType, byte[] bytes) {}

    sealed interface Answer<T> {
        record Answered<T>(T value, String model) implements Answer<T> {}

        /** No usable answer; {@code reason} is for logs and metrics, never shown to users. */
        record Unavailable<T>(String reason) implements Answer<T> {}
    }

    <T> Answer<T> ask(Task<T> task) {
        var tenant = TenantContext.current()
                .orElseThrow(() -> new IllegalStateException("AI calls are made on behalf of a tenant"));
        var input = redacted(task.input());
        var audited = new LinkedHashMap<String, Object>(input);
        if (!task.images().isEmpty()) {
            audited.put("images", imageHashes(task.images())); // which photos, by hash: the audit row stays small
        }
        var inputJson = json.writeValueAsString(audited);
        var converter = new BeanOutputConverter<>(task.answerType(), json);
        var prompt = render(task, input, converter.getFormat());
        var attempt = new Attempt(tenant, task, inputJson);

        // The replay key covers the images too: the same words with other photos is another question.
        var replayKey = task.images().isEmpty() ? prompt : prompt + "\nimages: " + imageHashes(task.images());
        var recorded = replays.find(task.feature(), task.promptVersion(), replayKey);
        if (recorded.isPresent()) {
            var answer = recorded.get();
            var parsed = parse(converter, answer.text());
            attempt.log(answer.model(), AiCallLog.Outcome.REPLAYED, answer.text(), null, answer, 0);
            return parsed == null
                    ? unavailable(task, "replayed answer is invalid")
                    : new Answer.Answered<>(parsed, answer.model());
        }
        if (replays.replaying()) {
            // Tests and CI: never call the provider. A missing recording is the feature's fallback, loudly.
            log.warn(
                    "No recorded AI answer for {} {}; run it once with replay mode 'record'",
                    task.feature(),
                    task.promptVersion());
            return unavailable(task, "no recording");
        }
        if (overBudget(tenant)) {
            attempt.log(
                    "-",
                    AiCallLog.Outcome.BUDGET_EXCEEDED,
                    null,
                    "daily budget of " + ai.dailyCallsPerTenant(),
                    null,
                    0);
            return unavailable(task, "daily budget");
        }

        long deadline = System.nanoTime() + ai.deadline().toNanos();
        String lastFailure = "no model available";
        models:
        for (var model : ai.modelsFor(task.feature())) {
            var breaker = breakers.circuitBreaker(model);
            for (int tryNumber = 1; tryNumber <= ai.attemptsPerModel(); tryNumber++) {
                if (breaker.getState() == CircuitBreaker.State.OPEN) {
                    attempt.log(model, AiCallLog.Outcome.CIRCUIT_OPEN, null, null, null, 0);
                    lastFailure = model + " circuit open";
                    continue models;
                }
                if (!rateLimiters.rateLimiter(model).acquirePermission()) { // every try is a request
                    attempt.log(model, AiCallLog.Outcome.RATE_LIMITED, null, null, null, 0);
                    lastFailure = model + " rate limited";
                    continue models;
                }
                if (System.nanoTime() + ai.callTimeout().toNanos() > deadline) {
                    // Only start a call that can finish (or time out) before the deadline: the deadline is a promise.
                    return unavailable(task, "deadline (" + lastFailure + ")");
                }
                long started = System.nanoTime();
                try {
                    var response = breaker.executeSupplier(() -> call(model, prompt, task.images()));
                    long millis = Duration.ofNanos(System.nanoTime() - started).toMillis();
                    metrics.tokens(
                            task.feature(), model, tenant, response, ai.models().get(model));
                    var parsed = parse(converter, response.text());
                    if (parsed == null) {
                        // The provider worked, the answer didn't: the next model may do better, while retrying
                        // this one with the same prompt mostly repeats the mistake.
                        attempt.log(
                                model,
                                AiCallLog.Outcome.INVALID_OUTPUT,
                                response.text(),
                                "answer doesn't match " + task.answerType().getSimpleName(),
                                response,
                                millis);
                        metrics.call(task.feature(), model, "invalid_output", millis);
                        lastFailure = model + " invalid output";
                        continue models;
                    }
                    attempt.log(model, AiCallLog.Outcome.OK, response.text(), null, response, millis);
                    metrics.call(task.feature(), model, "ok", millis);
                    replays.save(task.feature(), task.promptVersion(), replayKey, response);
                    return new Answer.Answered<>(parsed, model);
                } catch (CallNotPermittedException open) { // opened between the check and the call
                    attempt.log(model, AiCallLog.Outcome.CIRCUIT_OPEN, null, null, null, 0);
                    lastFailure = model + " circuit open";
                    continue models;
                } catch (ProviderException failure) {
                    long millis = Duration.ofNanos(System.nanoTime() - started).toMillis();
                    attempt.log(model, AiCallLog.Outcome.ERROR, null, failure.summary(), null, millis);
                    metrics.call(task.feature(), model, "error", millis);
                    lastFailure = model + " " + failure.summary();
                    if (!failure.retryable()) {
                        // A bad key or a bad request fails the same way on every model.
                        log.error("AI call for {} failed and won't be retried: {}", task.feature(), failure.summary());
                        return unavailable(task, lastFailure);
                    }
                    if (failure.timedOut()) {
                        continue models; // waiting again on an overloaded model would use up the deadline
                    }
                    if (tryNumber < ai.attemptsPerModel()) {
                        pause(tryNumber, deadline);
                    }
                }
            }
        }
        return unavailable(task, lastFailure);
    }

    /** Fails closed: if the budget can't be checked (database down), don't spend. */
    private boolean overBudget(UUID tenant) {
        try {
            return calls.providerCallsToday(tenant) >= ai.dailyCallsPerTenant();
        } catch (RuntimeException e) {
            log.warn("Couldn't check the AI budget: {}", e.toString());
            return true;
        }
    }

    /** One request to the provider; any failure on the way comes back as a {@link ProviderException}. */
    private ModelReply call(String model, String prompt, List<Image> images) {
        var options = GoogleGenAiChatOptions.builder()
                .model(model)
                .temperature(TEMPERATURE)
                .responseMimeType("application/json")
                .build();
        ChatResponse response;
        try {
            var message = UserMessage.builder()
                    .text(prompt)
                    .media(images.stream()
                            .map(image -> Media.builder()
                                    .mimeType(MimeType.valueOf(image.contentType()))
                                    .data(image.bytes())
                                    .build())
                            .toList())
                    .build();
            response = chatModel.call(new Prompt(message, options));
        } catch (RuntimeException e) {
            throw ProviderException.from(e);
        }
        // No result happens: a safety block, or an empty candidate list. That's an answer we can't use, not a crash.
        var result = response == null ? null : response.getResult();
        var text = result == null || result.getOutput() == null
                ? null
                : result.getOutput().getText();
        var usage = response == null ? null : response.getMetadata().getUsage();
        return new ModelReply(
                model,
                text,
                usage == null ? null : usage.getPromptTokens(),
                usage == null ? null : usage.getCompletionTokens());
    }

    private <T> String render(Task<T> task, Map<String, Object> input, String format) {
        var template =
                resources.getResource("classpath:prompts/" + task.feature() + "/" + task.promptVersion() + ".st");
        var variables = new LinkedHashMap<>(input);
        variables.put("format", format);
        return new PromptTemplate(template).render(variables);
    }

    private static <T> T parse(BeanOutputConverter<T> converter, String text) {
        try {
            return text == null ? null : converter.convert(text);
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static String imageHashes(List<Image> images) {
        return images.stream()
                .map(image -> ReplayCache.sha256(image.bytes()))
                .collect(java.util.stream.Collectors.joining(","));
    }

    private static Map<String, Object> redacted(Map<String, Object> input) {
        var result = new LinkedHashMap<String, Object>();
        input.forEach((key, value) -> result.put(key, value instanceof String text ? Redaction.redact(text) : value));
        return result;
    }

    /** Backoff with jitter (1 s, 2 s, ... +-25%), never past the deadline. */
    private void pause(int tryNumber, long deadline) {
        long base = ai.retryBackoff().toMillis() * tryNumber;
        long jittered = base + ThreadLocalRandom.current().nextLong(-base / 4, base / 4 + 1);
        long left = Duration.ofNanos(deadline - System.nanoTime()).toMillis();
        try {
            Thread.sleep(Math.max(0, Math.min(jittered, left)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private <T> Answer<T> unavailable(Task<T> task, String reason) {
        log.warn("AI {} unavailable, using the fallback: {}", task.feature(), reason);
        metrics.fallback(task.feature());
        return new Answer.Unavailable<>(reason);
    }

    /** Everything an audit row needs that stays the same across the attempts of one task. */
    private final class Attempt {
        private final UUID tenant;
        private final Task<?> task;
        private final String inputJson;

        Attempt(UUID tenant, Task<?> task, String inputJson) {
            this.tenant = tenant;
            this.task = task;
            this.inputJson = inputJson;
        }

        void log(String model, AiCallLog.Outcome outcome, String output, String error, ModelReply reply, long millis) {
            try {
                calls.record(new AiCallLog.Entry(
                        tenant,
                        task.feature(),
                        task.promptVersion(),
                        model,
                        outcome,
                        inputJson,
                        output,
                        error,
                        reply == null ? null : reply.inputTokens(),
                        reply == null ? null : reply.outputTokens(),
                        millis));
            } catch (RuntimeException e) {
                // The audit is important, but not more important than answering: the database being down
                // mustn't turn a working AI answer into an error. Metrics still count the call.
                AiGateway.log.error("Couldn't record an AI call for {}: {}", task.feature(), e.toString());
            }
        }
    }
}
