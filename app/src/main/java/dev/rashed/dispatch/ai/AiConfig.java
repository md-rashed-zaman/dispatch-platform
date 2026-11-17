package dev.rashed.dispatch.ai;

import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.google.genai.embedding.GoogleGenAiEmbeddingConnectionDetails;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false) // no calls between @Bean methods; avoids a startup warning
@EnableConfigurationProperties(AiProperties.class)
class AiConfig {

    private static final Logger log = LoggerFactory.getLogger(AiConfig.class);

    /**
     * Replaces Spring AI's client so the gateway owns timeouts and retries. Without this, a call has a long
     * default timeout and could be retried by the Google client and again by Spring AI's retry template (10
     * attempts, growing pauses), all hidden from us; we measured calls that hung for 40 s (docs/ai-limits.md).
     */
    @Bean
    Client googleGenAiClient(@Value("${spring.ai.google.genai.api-key}") String apiKey, AiProperties ai) {
        return Client.builder()
                .apiKey(apiKey)
                .httpOptions(HttpOptions.builder()
                        .timeout(Math.toIntExact(ai.callTimeout().toMillis()))
                        .retryOptions(HttpRetryOptions.builder().attempts(1).build())
                        .build())
                .build();
    }

    /** Embeddings use the same client, so the same timeout and no hidden retries. */
    @Bean
    GoogleGenAiEmbeddingConnectionDetails googleGenAiEmbeddingConnectionDetails(Client googleGenAiClient) {
        return GoogleGenAiEmbeddingConnectionDetails.builder()
                .genAiClient(googleGenAiClient)
                .build();
    }

    /**
     * One breaker per model. When half of the last 10 calls failed, stop calling that model for a while and go
     * straight to the next one, instead of making every user wait for a timeout first.
     */
    @Bean
    CircuitBreakerRegistry aiCircuitBreakers(@Value("${dispatch.ai.circuit.open-for:PT30S}") Duration openFor) {
        var registry = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(10)
                .minimumNumberOfCalls(4)
                .failureRateThreshold(50)
                .waitDurationInOpenState(openFor)
                .permittedNumberOfCallsInHalfOpenState(1)
                .recordExceptions(ProviderException.class)
                .build());
        registry.getEventPublisher()
                .onEntryAdded(added -> added.getAddedEntry()
                        .getEventPublisher()
                        .onStateTransition(event -> log.warn(
                                "AI circuit breaker {}: {}",
                                event.getCircuitBreakerName(),
                                event.getStateTransition())));
        return registry;
    }

    /**
     * Per-model requests per minute, below the provider's quota. A call that finds no permit doesn't wait: it
     * moves to the next model. shortcut: per app instance, so two instances may together reach the quota; move
     * the counter to Redis when running more than one.
     */
    @Bean
    RateLimiterRegistry aiRateLimiters(AiProperties ai) {
        var registry = RateLimiterRegistry.ofDefaults();
        ai.models()
                .forEach((model, settings) -> registry.rateLimiter(
                        model,
                        RateLimiterConfig.custom()
                                .limitForPeriod(settings.requestsPerMinute())
                                .limitRefreshPeriod(Duration.ofMinutes(1))
                                .timeoutDuration(Duration.ZERO)
                                .build()));
        return registry;
    }
}
