package dev.rashed.dispatch.ai;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How the gateway calls models. Values in application.yml come from the free-tier limits measured in step 3.1
 * (docs/ai-limits.md).
 *
 * @param callTimeout the longest one HTTP call to the provider may take
 * @param deadline the longest a whole feature call may take, across retries and fallback models
 * @param attemptsPerModel tries per model on a retryable error (503, 429, timeout) before the next model
 * @param dailyCallsPerTenant provider calls one company may cause per day (UTC); the quota is shared by all
 * @param models what the gateway knows about each model
 * @param features the models each feature tries, in order
 * @param replay record-and-replay of model answers, for tests and evals
 */
@ConfigurationProperties("dispatch.ai")
record AiProperties(
        Duration callTimeout,
        Duration deadline,
        int attemptsPerModel,
        Duration retryBackoff,
        int dailyCallsPerTenant,
        Map<String, Model> models,
        Map<String, List<String>> features,
        Replay replay) {

    /**
     * @param requestsPerMinute the client-side limit, kept below the provider's quota so we slow down before
     *     the provider starts answering 429
     * @param inputPricePerMillion list price in USD per million input tokens, for cost metrics
     */
    record Model(int requestsPerMinute, BigDecimal inputPricePerMillion, BigDecimal outputPricePerMillion) {}

    /**
     * @param mode {@code off} in production; {@code record} saves each answer; {@code replay} answers only from
     *     saved ones and never calls the provider (tests and CI)
     */
    record Replay(ReplayMode mode, Path directory) {}

    enum ReplayMode {
        OFF,
        RECORD,
        REPLAY
    }

    List<String> modelsFor(String feature) {
        var chain = features.get(feature);
        if (chain == null || chain.isEmpty()) {
            throw new IllegalStateException("No models configured for AI feature " + feature);
        }
        return chain;
    }
}
