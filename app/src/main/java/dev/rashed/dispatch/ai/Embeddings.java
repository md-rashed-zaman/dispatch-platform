package dev.rashed.dispatch.ai;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Text to vectors (step 3.4, T30), through the same controls as {@link AiGateway}: timeout, circuit breaker,
 * rate limit, retries on 503 and timeouts, metrics, and the replay cache for evals.
 *
 * <p>Two differences from chat. There's no fallback model: vectors from different models live in different
 * spaces and can't be compared, so the fallback is "no semantic score" and matching ranks without it. And there
 * is no audit row: technician profiles belong to no company, and a vector says nothing a person would read.
 */
@Component
public class Embeddings {

    private static final Logger log = LoggerFactory.getLogger(Embeddings.class);

    /**
     * What the text is, for retrieval: a technician profile is a document; a job description is the query
     * looking for matching documents. Gemini places the two kinds so that a query lands near its documents.
     */
    public enum Kind {
        DOCUMENT(GoogleGenAiTextEmbeddingOptions.TaskType.RETRIEVAL_DOCUMENT),
        QUERY(GoogleGenAiTextEmbeddingOptions.TaskType.RETRIEVAL_QUERY);

        private final GoogleGenAiTextEmbeddingOptions.TaskType taskType;

        Kind(GoogleGenAiTextEmbeddingOptions.TaskType taskType) {
            this.taskType = taskType;
        }
    }

    private final EmbeddingModel embeddingModel;
    private final AiProperties ai;
    private final ReplayCache replays;
    private final AiMetrics metrics;
    private final CircuitBreakerRegistry breakers;
    private final RateLimiterRegistry rateLimiters;
    private final JsonMapper json;
    private final String model;
    private final int dimensions;

    Embeddings(
            EmbeddingModel embeddingModel,
            AiProperties ai,
            ReplayCache replays,
            AiMetrics metrics,
            CircuitBreakerRegistry aiCircuitBreakers,
            RateLimiterRegistry aiRateLimiters,
            JsonMapper json,
            @Value("${dispatch.ai.embedding.model}") String model,
            @Value("${dispatch.ai.embedding.dimensions}") int dimensions) {
        this.embeddingModel = embeddingModel;
        this.ai = ai;
        this.replays = replays;
        this.metrics = metrics;
        this.breakers = aiCircuitBreakers;
        this.rateLimiters = aiRateLimiters;
        this.json = json;
        this.model = model;
        this.dimensions = dimensions;
    }

    /** Stored with every vector: only vectors of the same model may be compared. */
    public String model() {
        return model;
    }

    /**
     * One vector per text, in the same order, or empty when the provider can't answer within the limits. Texts
     * are redacted first, like every prompt.
     */
    public Optional<List<float[]>> embed(String feature, Kind kind, List<String> texts) {
        var redacted = texts.stream().map(Redaction::redact).toList();
        var vectors = new float[redacted.size()][];
        var missing = new ArrayList<Integer>();
        for (int i = 0; i < redacted.size(); i++) {
            var recorded = replays.find(feature, model + "-" + kind, redacted.get(i));
            if (recorded.isPresent()) {
                vectors[i] = json.readValue(recorded.get().text(), float[].class);
            } else {
                missing.add(i);
            }
        }
        if (missing.isEmpty()) {
            return Optional.of(Arrays.asList(vectors));
        }
        if (replays.replaying()) {
            log.warn(
                    "No recorded embedding for {} of {} texts ({}); run once with replay mode 'record'",
                    missing.size(),
                    texts.size(),
                    feature);
            metrics.fallback(feature);
            return Optional.empty();
        }
        var asked = missing.stream().map(redacted::get).toList();
        var answer = call(feature, kind, asked);
        if (answer.isEmpty()) {
            metrics.fallback(feature);
            return Optional.empty();
        }
        for (int j = 0; j < missing.size(); j++) {
            var vector = answer.get().get(j);
            vectors[missing.get(j)] = vector;
            replays.save(
                    feature,
                    model + "-" + kind,
                    asked.get(j),
                    new ModelReply(model, json.writeValueAsString(vector), null, null));
        }
        return Optional.of(Arrays.asList(vectors));
    }

    private Optional<List<float[]>> call(String feature, Kind kind, List<String> texts) {
        var breaker = breakers.circuitBreaker(model);
        var options = GoogleGenAiTextEmbeddingOptions.builder()
                .model(model)
                .taskType(kind.taskType)
                .dimensions(dimensions)
                .build();
        long deadline = System.nanoTime() + ai.deadline().toNanos();
        for (int tryNumber = 1; tryNumber <= ai.attemptsPerModel(); tryNumber++) {
            if (breaker.getState() == CircuitBreaker.State.OPEN) {
                log.warn("Embeddings for {} skipped: {} circuit open", feature, model);
                return Optional.empty();
            }
            // The free tier counts every text in a batch as one request (100 a minute, 1,000 a day), so permits
            // are per text, not per call.
            if (!rateLimiters.rateLimiter(model).acquirePermission(texts.size())) {
                log.warn("Embeddings for {} skipped: {} rate limited", feature, model);
                return Optional.empty();
            }
            if (System.nanoTime() + ai.callTimeout().toNanos() > deadline) {
                return Optional.empty();
            }
            long started = System.nanoTime();
            try {
                var response = breaker.executeSupplier(() -> {
                    try {
                        return embeddingModel.call(new EmbeddingRequest(texts, options));
                    } catch (RuntimeException e) {
                        throw ProviderException.from(e);
                    }
                });
                long millis = Duration.ofNanos(System.nanoTime() - started).toMillis();
                var results = response.getResults();
                if (results.size() != texts.size()
                        || results.stream()
                                .anyMatch(r -> r.getOutput() == null || r.getOutput().length != dimensions)) {
                    metrics.call(feature, model, "invalid_output", millis);
                    log.warn(
                            "Embeddings for {}: {} vectors for {} texts, or the wrong size",
                            feature,
                            results.size(),
                            texts.size());
                    return Optional.empty();
                }
                metrics.call(feature, model, "ok", millis);
                return Optional.of(results.stream().map(r -> r.getOutput()).toList());
            } catch (CallNotPermittedException open) {
                return Optional.empty();
            } catch (ProviderException failure) {
                long millis = Duration.ofNanos(System.nanoTime() - started).toMillis();
                metrics.call(feature, model, "error", millis);
                log.warn("Embeddings for {} failed (try {}): {}", feature, tryNumber, failure.summary());
                if (!failure.retryable() || failure.timedOut()) {
                    return Optional.empty();
                }
                sleep(ai.retryBackoff().multipliedBy(tryNumber));
            }
        }
        return Optional.empty();
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
