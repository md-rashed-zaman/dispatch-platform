package dev.rashed.dispatch.assistant;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The {@code ai_tool_call} audit (V22), and one-time confirmations for write tools. A confirmation token is shown
 * to the client once; only its SHA-256 is stored, bound to the user and to exactly what it confirms (the tool and
 * its arguments), valid for five minutes, usable once.
 */
@Component
class ToolCalls {

    static final Duration CONFIRMATION_LIFETIME = Duration.ofMinutes(5);

    enum Outcome {
        OK,
        NEEDS_CONFIRMATION,
        CONFIRMED,
        DENIED,
        NOT_FOUND,
        REJECTED,
        ERROR
    }

    record Confirmation(String token, Instant expiresAt) {}

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient jdbc;

    ToolCalls(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Its own transaction: the record stays even when the tool's own work rolls back. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void record(
            @Nullable UUID tenant, UUID user, String tool, String arguments, Outcome outcome, @Nullable String detail) {
        insert(tenant, user, tool, arguments, outcome, detail, null, null, null);
    }

    /** A preview was shown: issues the one-time token that lets this user confirm exactly this call. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    Confirmation issue(
            @Nullable UUID tenant, UUID user, String tool, String arguments, String confirms, String detail) {
        var bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        var token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        var expiresAt = Instant.now().plus(CONFIRMATION_LIFETIME);
        insert(tenant, user, tool, arguments, Outcome.NEEDS_CONFIRMATION, detail, sha256(token), confirms, expiresAt);
        return new Confirmation(token, expiresAt);
    }

    /**
     * Uses a token, in the caller's transaction (with the write it confirms, so a failed write gives it back): true
     * only for this user, this exact call, unexpired and unused.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    boolean consume(UUID user, String token, String confirms) {
        return jdbc.sql("""
                        UPDATE ai_tool_call SET confirmed_at = now()
                        WHERE confirmation_hash = :hash AND user_id = :user AND confirms_hash = :confirms
                          AND expires_at > now() AND confirmed_at IS NULL
                        """)
                        .param("hash", sha256(token))
                        .param("user", user)
                        .param("confirms", confirms)
                        .update()
                == 1;
    }

    private void insert(
            @Nullable UUID tenant,
            UUID user,
            String tool,
            String arguments,
            Outcome outcome,
            @Nullable String detail,
            @Nullable String confirmationHash,
            @Nullable String confirms,
            @Nullable Instant expiresAt) {
        jdbc.sql("""
                        INSERT INTO ai_tool_call (id, tenant_id, user_id, tool, arguments, outcome, detail,
                                                  confirmation_hash, confirms_hash, expires_at)
                        VALUES (:id, :tenant, :user, :tool, CAST(:arguments AS jsonb), :outcome, :detail,
                                :hash, :confirms, :expires)
                        """)
                .param("id", UUID.randomUUID())
                .param("tenant", tenant)
                .param("user", user)
                .param("tool", tool)
                .param("arguments", arguments)
                .param("outcome", outcome.name())
                .param("detail", detail == null ? null : detail.length() > 1000 ? detail.substring(0, 1000) : detail)
                .param("hash", confirmationHash)
                .param("confirms", confirms)
                .param("expires", expiresAt == null ? null : java.sql.Timestamp.from(expiresAt))
                .update();
    }

    static String sha256(String text) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
