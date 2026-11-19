package dev.rashed.dispatch.flags;

import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.FeatureProvider;
import dev.openfeature.sdk.Metadata;
import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.Reason;
import dev.openfeature.sdk.Value;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Our flags behind the OpenFeature standard API (openfeature.dev): code asks an OpenFeature {@code Client}, never
 * this class, so moving to a flag vendor later means registering their provider instead, without touching the
 * code that reads flags. The targeting key is the company's id; flags here are boolean.
 */
class DispatchFlagProvider implements FeatureProvider {

    private final FlagStore store;

    DispatchFlagProvider(FlagStore store) {
        this.store = store;
    }

    @Override
    public Metadata getMetadata() {
        return () -> "dispatch-flags";
    }

    @Override
    public ProviderEvaluation<Boolean> getBooleanEvaluation(String key, Boolean defaultValue, EvaluationContext ctx) {
        var flag = store.get(key);
        if (flag.isEmpty()) {
            return ProviderEvaluation.<Boolean>builder()
                    .value(defaultValue)
                    .reason(Reason.ERROR.toString())
                    .errorCode(ErrorCode.FLAG_NOT_FOUND)
                    .errorMessage("No flag " + key)
                    .build();
        }
        var evaluation = flag.get().evaluate(tenant(ctx));
        return ProviderEvaluation.<Boolean>builder()
                .value(evaluation.on())
                .variant(evaluation.variant())
                .reason(evaluation.reason().name())
                .build();
    }

    @Override
    public ProviderEvaluation<String> getStringEvaluation(String key, String defaultValue, EvaluationContext ctx) {
        return wrongType(defaultValue);
    }

    @Override
    public ProviderEvaluation<Integer> getIntegerEvaluation(String key, Integer defaultValue, EvaluationContext ctx) {
        return wrongType(defaultValue);
    }

    @Override
    public ProviderEvaluation<Double> getDoubleEvaluation(String key, Double defaultValue, EvaluationContext ctx) {
        return wrongType(defaultValue);
    }

    @Override
    public ProviderEvaluation<Value> getObjectEvaluation(String key, Value defaultValue, EvaluationContext ctx) {
        return wrongType(defaultValue);
    }

    private static <T> ProviderEvaluation<T> wrongType(T defaultValue) {
        return ProviderEvaluation.<T>builder()
                .value(defaultValue)
                .reason(Reason.ERROR.toString())
                .errorCode(ErrorCode.TYPE_MISMATCH)
                .errorMessage("Dispatch flags are boolean")
                .build();
    }

    private static @Nullable UUID tenant(@Nullable EvaluationContext ctx) {
        var key = ctx == null ? null : ctx.getTargetingKey();
        if (key == null || key.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(key);
        } catch (IllegalArgumentException notACompany) {
            return null;
        }
    }
}
