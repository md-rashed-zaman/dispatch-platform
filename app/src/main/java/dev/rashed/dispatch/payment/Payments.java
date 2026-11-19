package dev.rashed.dispatch.payment;

import dev.rashed.dispatch.shared.Money;
import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The {@code payment} table (V19). Row-level security: call as the payment's tenant, inside a transaction. Every
 * state change is a conditional update from the states it may leave, so concurrent confirmations (the provider's
 * notice and the browser's return at the same moment) change a payment once.
 */
@Component
class Payments {

    enum State {
        INITIATED,
        PENDING,
        CAPTURED,
        FAILED,
        EXPIRED;

        boolean isOpen() {
            return this == INITIATED || this == PENDING;
        }
    }

    record Payment(
            UUID id,
            UUID tenantId,
            UUID workOrderId,
            String tranId,
            State state,
            Money amount,
            @Nullable URI gatewayUrl,
            @Nullable String reason,
            Instant createdAt,
            @Nullable Instant settledAt) {}

    /** What the provider said when the payment settled. */
    record Settlement(@Nullable String valId, @Nullable String bankTranId, String providerStatus, int riskLevel) {}

    private final JdbcClient jdbc;

    Payments(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * A new INITIATED payment, or empty when the work order already has one that is open or captured (the
     * partial unique index decides, so two requests at once can't both create one).
     */
    Optional<Payment> insert(UUID id, UUID tenantId, UUID workOrderId, Money amount, @Nullable UUID createdBy) {
        var inserted = jdbc.sql("""
                        INSERT INTO payment (id, tenant_id, work_order_id, tran_id, status, amount_minor, currency, created_by)
                        VALUES (:id, :tenant, :workOrder, :tranId, 'INITIATED', :minor, :currency, :createdBy)
                        ON CONFLICT (work_order_id) WHERE status IN ('INITIATED', 'PENDING', 'CAPTURED') DO NOTHING
                        """)
                .param("id", id)
                .param("tenant", tenantId)
                .param("workOrder", workOrderId)
                .param("tranId", tranId(id))
                .param("minor", amount.minor())
                .param("currency", amount.currency())
                .param("createdBy", createdBy)
                .update();
        return inserted == 1 ? find(id) : Optional.empty();
    }

    /** Our id at the provider: the payment id in base 36, 25 characters at most (SSLCommerz allows 30). */
    static String tranId(UUID id) {
        return new java.math.BigInteger(id.toString().replace("-", ""), 16).toString(36);
    }

    Optional<Payment> find(UUID id) {
        return select("WHERE id = :id").param("id", id).query(Payments::payment).optional();
    }

    Optional<Payment> byTranId(String tranId) {
        return select("WHERE tran_id = :tranId")
                .param("tranId", tranId)
                .query(Payments::payment)
                .optional();
    }

    /** The work order's open or captured payment, if any. */
    Optional<Payment> live(UUID workOrderId) {
        return select("WHERE work_order_id = :workOrder AND status IN ('INITIATED', 'PENDING', 'CAPTURED')")
                .param("workOrder", workOrderId)
                .query(Payments::payment)
                .optional();
    }

    /** The newest payment of a work order, whatever its state: what its page shows. */
    Optional<Payment> latest(UUID workOrderId) {
        return select("WHERE work_order_id = :workOrder ORDER BY created_at DESC LIMIT 1")
                .param("workOrder", workOrderId)
                .query(Payments::payment)
                .optional();
    }

    /** Open payments created before {@code before}, oldest first: the reconciler's work. */
    List<Payment> openSince(Instant before, int limit) {
        return select(
                        "WHERE status IN ('INITIATED', 'PENDING') AND created_at < :before ORDER BY created_at LIMIT :limit")
                .param("before", Timestamp.from(before))
                .param("limit", limit)
                .query(Payments::payment)
                .list();
    }

    /** Payments that changed since {@code since}, or are captured: the daily reconciliation. */
    List<Payment> changedSince(Instant since) {
        return select("WHERE updated_at >= :since ORDER BY created_at")
                .param("since", Timestamp.from(since))
                .query(Payments::payment)
                .list();
    }

    boolean pending(UUID id, String sessionKey, URI gatewayUrl) {
        return jdbc.sql("""
                        UPDATE payment SET status = 'PENDING', session_key = :session, gateway_url = :url, updated_at = now()
                        WHERE id = :id AND status = 'INITIATED'
                        """)
                        .param("id", id)
                        .param("session", sessionKey)
                        .param("url", gatewayUrl.toString())
                        .update()
                == 1;
    }

    /** Moves an open payment to a final state; false if it was already final (someone else settled it). */
    boolean settle(UUID id, State to, @Nullable String reason, @Nullable Settlement settlement) {
        return jdbc.sql("""
                        UPDATE payment
                        SET status = :to, reason = :reason, settled_at = now(), updated_at = now(),
                            val_id = :valId, bank_tran_id = :bankTranId, provider_status = :providerStatus,
                            risk_level = :risk
                        WHERE id = :id AND status IN ('INITIATED', 'PENDING')
                        """)
                        .param("id", id)
                        .param("to", to.name())
                        .param("reason", reason)
                        .param("valId", settlement == null ? null : settlement.valId())
                        .param("bankTranId", settlement == null ? null : settlement.bankTranId())
                        .param("providerStatus", settlement == null ? null : settlement.providerStatus())
                        .param("risk", settlement == null ? null : settlement.riskLevel())
                        .update()
                == 1;
    }

    /** Keeps an open payment open, with why it waits ("risk review"). */
    void note(UUID id, String reason) {
        jdbc.sql(
                        "UPDATE payment SET reason = :reason, updated_at = now() WHERE id = :id AND status IN ('INITIATED', 'PENDING')")
                .param("id", id)
                .param("reason", reason)
                .update();
    }

    /** Records a disagreement with the provider for a person to look at; once per kind per payment. */
    boolean mismatch(Payment payment, String providerStatus, String detail) {
        return jdbc.sql("""
                        INSERT INTO payment_mismatch (tenant_id, payment_id, local_status, provider_status, detail)
                        VALUES (:tenant, :payment, :local, :provider, :detail)
                        ON CONFLICT (payment_id, local_status, provider_status) DO NOTHING
                        """)
                        .param("tenant", payment.tenantId())
                        .param("payment", payment.id())
                        .param("local", payment.state().name())
                        .param("provider", providerStatus)
                        .param("detail", detail)
                        .update()
                == 1;
    }

    private JdbcClient.StatementSpec select(String where) {
        return jdbc.sql("""
                SELECT id, tenant_id, work_order_id, tran_id, status, amount_minor, currency, gateway_url, reason,
                       created_at, settled_at
                FROM payment
                """ + where);
    }

    private static Payment payment(ResultSet rs, int row) throws SQLException {
        var url = rs.getString("gateway_url");
        var settled = rs.getTimestamp("settled_at");
        return new Payment(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("work_order_id", UUID.class),
                rs.getString("tran_id"),
                State.valueOf(rs.getString("status")),
                new Money(rs.getLong("amount_minor"), rs.getString("currency")),
                url == null ? null : URI.create(url),
                rs.getString("reason"),
                rs.getTimestamp("created_at").toInstant(),
                settled == null ? null : settled.toInstant());
    }
}
