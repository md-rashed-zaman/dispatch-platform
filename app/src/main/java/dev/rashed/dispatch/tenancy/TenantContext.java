package dev.rashed.dispatch.tenancy;

import dev.rashed.dispatch.identity.CurrentUser;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Which tenant the current thread works for. In a web request it comes from the verified access token's
 * {@code tenant_id} claim, never from request input. Work without a request (jobs, event consumers, tests)
 * names its tenant explicitly with {@link #callAs}.
 *
 * <p>Read when a transaction starts (see {@link TenancyConfig}), so set it before opening the transaction.
 * Thread-bound: code handed to another thread ({@code @Async}, executors) starts with no tenant.
 */
public final class TenantContext {

    private static final ThreadLocal<UUID> EXPLICIT = new ThreadLocal<>();

    /** Empty for callers outside any tenant (technicians, platform admins, no caller at all). */
    public static Optional<UUID> current() {
        var explicit = EXPLICIT.get();
        if (explicit != null) {
            return Optional.of(explicit);
        }
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            return Optional.ofNullable(CurrentUser.from(jwt).tenantId());
        }
        return Optional.empty();
    }

    /** Runs {@code action} as {@code tenantId}, then restores whatever was current before. */
    public static <T> T callAs(UUID tenantId, Supplier<T> action) {
        var previous = EXPLICIT.get();
        EXPLICIT.set(Objects.requireNonNull(tenantId, "tenantId"));
        try {
            return action.get();
        } finally {
            if (previous == null) {
                EXPLICIT.remove();
            } else {
                EXPLICIT.set(previous);
            }
        }
    }

    public static void runAs(UUID tenantId, Runnable action) {
        callAs(tenantId, () -> {
            action.run();
            return null;
        });
    }

    private TenantContext() {}
}
