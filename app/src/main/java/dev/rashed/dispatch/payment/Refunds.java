package dev.rashed.dispatch.payment;

import dev.rashed.dispatch.shared.Money;
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

/** The {@code refund} table (V20). Row-level security: call as the refund's company, inside a transaction. */
@Component
class Refunds {

    enum State {
        REQUESTED,
        PROCESSING,
        REFUNDED,
        FAILED
    }

    record Refund(
            UUID id,
            UUID tenantId,
            UUID paymentId,
            Money amount,
            String refundTransId,
            @Nullable String refundRefId,
            State state,
            String reason,
            Instant createdAt) {}

    private final JdbcClient jdbc;

    Refunds(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A REQUESTED refund of the whole payment; empty if it already has one (one refund per payment). */
    Optional<Refund> request(UUID id, UUID tenantId, UUID paymentId, Money amount, String reason) {
        var inserted = jdbc.sql("""
                        INSERT INTO refund (id, tenant_id, payment_id, amount_minor, currency, refund_trans_id, status, reason)
                        VALUES (:id, :tenant, :payment, :minor, :currency, :transId, 'REQUESTED', :reason)
                        ON CONFLICT (payment_id) DO NOTHING
                        """)
                .param("id", id)
                .param("tenant", tenantId)
                .param("payment", paymentId)
                .param("minor", amount.minor())
                .param("currency", amount.currency())
                .param("transId", "R" + Payments.tranId(id)) // 26 characters at most: SSLCommerz allows 30
                .param("reason", reason)
                .update();
        return inserted == 1 ? find(id) : Optional.empty();
    }

    Optional<Refund> find(UUID id) {
        return select("WHERE id = :id").param("id", id).query(Refunds::refund).optional();
    }

    Optional<Refund> forPayment(UUID paymentId) {
        return select("WHERE payment_id = :payment")
                .param("payment", paymentId)
                .query(Refunds::refund)
                .optional();
    }

    List<Refund> open(Instant before, int limit) {
        return select(
                        "WHERE status IN ('REQUESTED', 'PROCESSING') AND updated_at < :before ORDER BY created_at LIMIT :limit")
                .param("before", Timestamp.from(before))
                .param("limit", limit)
                .query(Refunds::refund)
                .list();
    }

    boolean processing(UUID id, String refundRefId) {
        return jdbc.sql("""
                        UPDATE refund SET status = 'PROCESSING', refund_ref_id = :ref, updated_at = now()
                        WHERE id = :id AND status = 'REQUESTED'
                        """).param("id", id).param("ref", refundRefId).update() == 1;
    }

    /** Final: REFUNDED or FAILED. False if it was already final. */
    boolean settle(UUID id, State to, @Nullable String providerNote) {
        return jdbc.sql("""
                        UPDATE refund SET status = :to, provider_note = :note, settled_at = now(), updated_at = now()
                        WHERE id = :id AND status IN ('REQUESTED', 'PROCESSING')
                        """)
                        .param("id", id)
                        .param("to", to.name())
                        .param("note", providerNote)
                        .update()
                == 1;
    }

    /** Asked about, still open: pushes it back in the queue so the others get their turn. */
    void touch(UUID id) {
        jdbc.sql("UPDATE refund SET updated_at = now() WHERE id = :id")
                .param("id", id)
                .update();
    }

    private JdbcClient.StatementSpec select(String where) {
        return jdbc.sql("""
                SELECT id, tenant_id, payment_id, amount_minor, currency, refund_trans_id, refund_ref_id, status, reason,
                       created_at
                FROM refund
                """ + where);
    }

    private static Refund refund(ResultSet rs, int row) throws SQLException {
        return new Refund(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("payment_id", UUID.class),
                new Money(rs.getLong("amount_minor"), rs.getString("currency")),
                rs.getString("refund_trans_id"),
                rs.getString("refund_ref_id"),
                State.valueOf(rs.getString("status")),
                rs.getString("reason"),
                rs.getTimestamp("created_at").toInstant());
    }
}
