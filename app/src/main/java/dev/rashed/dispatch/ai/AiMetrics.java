package dev.rashed.dispatch.ai;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Duration;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Cost and health of AI features, per feature, model and tenant (T25, T28). The tenant tag is fine here:
 * tenants are companies, a few hundred at most, not end users.
 */
@Component
class AiMetrics {

    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    private final MeterRegistry registry;

    AiMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** Outcome is ok, error or invalid_output; latency only counts calls that reached the provider. */
    void call(String feature, String model, String outcome, long millis) {
        Timer.builder("dispatch.ai.calls")
                .description("Model calls by feature, model and outcome")
                .tags("feature", feature, "model", model, "outcome", outcome)
                .publishPercentileHistogram()
                .register(registry)
                .record(Duration.ofMillis(millis));
    }

    /** Tokens and what they cost at list price (the free tier charges nothing, but the number is the plan). */
    void tokens(String feature, String model, UUID tenant, ModelReply reply, AiProperties.Model prices) {
        var tenantId = tenant.toString();
        int input = reply.inputTokens() == null ? 0 : reply.inputTokens();
        int output = reply.outputTokens() == null ? 0 : reply.outputTokens();
        counter("dispatch.ai.tokens", "Tokens used", feature, model, tenantId, "kind", "input")
                .increment(input);
        counter("dispatch.ai.tokens", "Tokens used", feature, model, tenantId, "kind", "output")
                .increment(output);
        var usd = cost(reply, prices);
        if (usd != null) {
            counter("dispatch.ai.cost.usd", "Cost at list price, USD", feature, model, tenantId)
                    .increment(usd.doubleValue());
        }
    }

    /** What a reply cost at list price; null without a reply or a price. */
    static BigDecimal cost(ModelReply reply, AiProperties.Model prices) {
        if (reply == null || prices == null) {
            return null;
        }
        int input = reply.inputTokens() == null ? 0 : reply.inputTokens();
        int output = reply.outputTokens() == null ? 0 : reply.outputTokens();
        return prices.inputPricePerMillion()
                .multiply(BigDecimal.valueOf(input))
                .add(prices.outputPricePerMillion().multiply(BigDecimal.valueOf(output)))
                .divide(MILLION, MathContext.DECIMAL64);
    }

    /** A feature gave up on AI and used its non-AI path. */
    void fallback(String feature) {
        Counter.builder("dispatch.ai.fallbacks")
                .description("Feature calls that used the non-AI fallback")
                .tag("feature", feature)
                .register(registry)
                .increment();
    }

    private Counter counter(
            String name, String description, String feature, String model, String tenant, String... extra) {
        return Counter.builder(name)
                .description(description)
                .tags("feature", feature, "model", model, "tenant", tenant)
                .tags(extra)
                .register(registry);
    }
}
