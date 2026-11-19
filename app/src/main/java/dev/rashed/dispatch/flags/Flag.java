package dev.rashed.dispatch.flags;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One flag and its rules (V21). Evaluation is a pure function of the flag and the company, so every instance of
 * the app, and every repeat, gives the same answer.
 */
public record Flag(
        String key,
        String description,
        boolean enabled,
        int rolloutPercent,
        Set<UUID> allowTenants,
        Set<UUID> denyTenants,
        long version,
        Instant updatedAt,
        @Nullable UUID updatedBy) {

    public Flag {
        if (rolloutPercent < 0 || rolloutPercent > 100) {
            throw new IllegalArgumentException("Rollout must be 0 to 100%: " + rolloutPercent);
        }
        allowTenants = Set.copyOf(allowTenants);
        denyTenants = Set.copyOf(denyTenants);
    }

    /** Why a flag is on or off for a company, in OpenFeature's words. */
    public enum Reason {
        /** The kill switch is off. */
        DISABLED,
        /** On the allow or the deny list. */
        TARGETING_MATCH,
        /** Decided by the rollout percentage. */
        SPLIT,
        /** No company to decide for: off. */
        DEFAULT
    }

    public record Evaluation(
            boolean on, Reason reason, @Nullable Integer bucket) {

        public String variant() {
            return on ? "on" : "off";
        }
    }

    public Evaluation evaluate(@Nullable UUID tenant) {
        if (!enabled) {
            return new Evaluation(false, Reason.DISABLED, null);
        }
        if (tenant == null) {
            return new Evaluation(false, Reason.DEFAULT, null);
        }
        if (denyTenants.contains(tenant)) {
            return new Evaluation(false, Reason.TARGETING_MATCH, null);
        }
        if (allowTenants.contains(tenant)) {
            return new Evaluation(true, Reason.TARGETING_MATCH, null);
        }
        var bucket = bucket(key, tenant);
        return new Evaluation(bucket < rolloutPercent, Reason.SPLIT, bucket);
    }

    /**
     * 0 to 99, the same for a flag and a company forever: a hash, not a random draw, so a company never flips
     * groups between requests or instances, and growing the rollout from 20% to 50% only adds companies (those in
     * buckets 20 to 49), never swaps them. The flag's key is in the hash, so different flags pick different
     * companies.
     */
    public static int bucket(String flagKey, UUID tenant) {
        try {
            var digest = MessageDigest.getInstance("SHA-256")
                    .digest((flagKey + ":" + tenant).getBytes(StandardCharsets.UTF_8));
            long first8 = 0;
            for (int i = 0; i < 8; i++) {
                first8 = (first8 << 8) | (digest[i] & 0xFF);
            }
            return (int) Long.remainderUnsigned(first8, 100);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
