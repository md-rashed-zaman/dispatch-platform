package dev.rashed.dispatch.payment;

import dev.rashed.dispatch.shared.Money;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** The {@code payout} table (V20). Row-level security: call as the payout's company, inside a transaction. */
@Component
class Payouts {

    record Payout(
            UUID id,
            UUID tenantId,
            UUID workOrderId,
            UUID technicianId,
            Money amount,
            String status,
            @Nullable String bankReference,
            Instant createdAt,
            @Nullable Instant paidAt) {}

    private final JdbcClient jdbc;

    Payouts(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** One per work order: a second call (a retried listener) changes nothing. */
    void create(UUID id, UUID tenantId, UUID workOrderId, UUID technicianId, Money amount) {
        jdbc.sql("""
                        INSERT INTO payout (id, tenant_id, work_order_id, technician_id, amount_minor, currency, status)
                        VALUES (:id, :tenant, :workOrder, :technician, :minor, :currency, 'PENDING')
                        ON CONFLICT (work_order_id) DO NOTHING
                        """)
                .param("id", id)
                .param("tenant", tenantId)
                .param("workOrder", workOrderId)
                .param("technician", technicianId)
                .param("minor", amount.minor())
                .param("currency", amount.currency())
                .update();
    }

    Optional<Payout> find(UUID id) {
        return select("WHERE id = :id").param("id", id).query(Payouts::payout).optional();
    }

    Optional<Payout> forWorkOrder(UUID workOrderId) {
        return select("WHERE work_order_id = :workOrder")
                .param("workOrder", workOrderId)
                .query(Payouts::payout)
                .optional();
    }

    List<Payout> pending() {
        return select("WHERE status = 'PENDING' ORDER BY created_at")
                .query(Payouts::payout)
                .list();
    }

    /** PENDING to PAID; false if it was already paid. */
    boolean paid(UUID id, String bankReference, @Nullable UUID by) {
        return jdbc.sql("""
                        UPDATE payout SET status = 'PAID', bank_reference = :reference, paid_by = :by, paid_at = now()
                        WHERE id = :id AND status = 'PENDING'
                        """)
                        .param("id", id)
                        .param("reference", bankReference)
                        .param("by", by)
                        .update()
                == 1;
    }

    private JdbcClient.StatementSpec select(String where) {
        return jdbc.sql("""
                SELECT id, tenant_id, work_order_id, technician_id, amount_minor, currency, status, bank_reference,
                       created_at, paid_at
                FROM payout
                """ + where);
    }

    private static Payout payout(ResultSet rs, int row) throws SQLException {
        var paid = rs.getTimestamp("paid_at");
        return new Payout(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("work_order_id", UUID.class),
                rs.getObject("technician_id", UUID.class),
                new Money(rs.getLong("amount_minor"), rs.getString("currency")),
                rs.getString("status"),
                rs.getString("bank_reference"),
                rs.getTimestamp("created_at").toInstant(),
                paid == null ? null : paid.toInstant());
    }
}
