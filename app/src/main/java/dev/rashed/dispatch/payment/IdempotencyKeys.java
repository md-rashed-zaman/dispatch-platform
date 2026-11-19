package dev.rashed.dispatch.payment;

import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code Idempotency-Key} for requests that move money (ADR 0016, T19). The first request with a key claims it
 * with a hash of itself; when it's done, its response is stored with the key. A retry with the same key gets:
 *
 * <ul>
 *   <li>the stored response, if the first one finished and the request is the same;
 *   <li>409 "in progress", if the first one is still running (the client tries again shortly);
 *   <li>422, if the same key comes with a different request: a client bug, never "the same request".
 * </ul>
 *
 * Each step is its own short transaction, so the claim is visible to concurrent requests at once and no database
 * connection is held while the provider is called. A request that fails with an error gives the key back, so a
 * retry runs again; what keeps that retry from paying twice is the payment's own rule (one live payment per work
 * order), not the key.
 */
@Component
class IdempotencyKeys {

    sealed interface Claim {
        /** This request owns the key: do the work, then {@link #complete} or {@link #release}. */
        record Claimed() implements Claim {}

        record Replay(int status, String body) implements Claim {}

        record InProgress() implements Claim {}

        record Mismatch() implements Claim {}
    }

    private final JdbcClient jdbc;

    IdempotencyKeys(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Claim claim(UUID tenantId, String key, String scope, String requestHash) {
        var claimed = jdbc.sql("""
                        INSERT INTO idempotency_key (tenant_id, idem_key, scope, request_hash, status)
                        VALUES (:tenant, :key, :scope, :hash, 'IN_PROGRESS')
                        ON CONFLICT (tenant_id, idem_key) DO NOTHING
                        """)
                .param("tenant", tenantId)
                .param("key", key)
                .param("scope", scope)
                .param("hash", requestHash)
                .update();
        if (claimed == 1) {
            return new Claim.Claimed();
        }
        return jdbc.sql("""
                        SELECT scope, request_hash, status, response_status, response_body::text AS body
                        FROM idempotency_key WHERE tenant_id = :tenant AND idem_key = :key
                        """)
                .param("tenant", tenantId)
                .param("key", key)
                .query((rs, row) -> {
                    if (!scope.equals(rs.getString("scope")) || !requestHash.equals(rs.getString("request_hash"))) {
                        return (Claim) new Claim.Mismatch();
                    }
                    if ("IN_PROGRESS".equals(rs.getString("status"))) {
                        return new Claim.InProgress();
                    }
                    return new Claim.Replay(rs.getInt("response_status"), rs.getString("body"));
                })
                .optional()
                // Released between our insert and this read: the caller may simply try again.
                .orElseGet(Claim.InProgress::new);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(UUID tenantId, String key, int status, @Nullable String body) {
        jdbc.sql("""
                        UPDATE idempotency_key
                        SET status = 'COMPLETED', response_status = :status, response_body = CAST(:body AS jsonb),
                            completed_at = now()
                        WHERE tenant_id = :tenant AND idem_key = :key AND status = 'IN_PROGRESS'
                        """)
                .param("tenant", tenantId)
                .param("key", key)
                .param("status", status)
                .param("body", body)
                .update();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(UUID tenantId, String key) {
        jdbc.sql("DELETE FROM idempotency_key WHERE tenant_id = :tenant AND idem_key = :key AND status = 'IN_PROGRESS'")
                .param("tenant", tenantId)
                .param("key", key)
                .update();
    }
}
