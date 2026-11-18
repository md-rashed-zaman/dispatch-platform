package dev.rashed.dispatch.review;

import dev.rashed.dispatch.ai.CompletionCheck;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** The {@code completion_audit} table (V17). Row-level security: call as the work order's tenant. */
@Component
class CompletionAudits {

    enum State {
        NEEDS_REVIEW,
        AUTO_APPROVED,
        APPROVED,
        REJECTED,
        DECIDED_ELSEWHERE
    }

    /** An audit as the API shows it. */
    record Audit(
            UUID id,
            UUID workOrderId,
            State state,
            @Nullable String model,
            String promptVersion,
            List<CompletionCheck.Item> items,
            @Nullable BigDecimal confidence,
            int photoCount,
            AuditPolicy.Recommendation recommendation,
            String reason,
            @Nullable String decidedBy,
            @Nullable String decisionReason,
            @Nullable Instant decidedAt,
            Instant createdAt) {}

    private static final TypeReference<List<CompletionCheck.Item>> ITEMS = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final JsonMapper json;

    CompletionAudits(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional(readOnly = true)
    boolean exists(UUID sourceEventId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM completion_audit WHERE source_event_id = :event)")
                .param("event", sourceEventId)
                .query(Boolean.class)
                .single();
    }

    /** False when this submission was audited already (a redelivered event). */
    @Transactional
    boolean insert(
            UUID id,
            UUID tenantId,
            UUID workOrderId,
            UUID sourceEventId,
            Optional<CompletionCheck.Assessment> assessment,
            int photoCount,
            AuditPolicy.Decision decision) {
        return jdbc.sql("""
                        INSERT INTO completion_audit (id, tenant_id, work_order_id, source_event_id, state, model,
                                                      prompt_version, items, confidence, photo_count, recommendation,
                                                      reason)
                        VALUES (:id, :tenant, :workOrder, :event, 'NEEDS_REVIEW', :model, :version, CAST(:items AS jsonb),
                                :confidence, :photos, :recommendation, :reason)
                        ON CONFLICT (source_event_id) DO NOTHING
                        """)
                        .param("id", id)
                        .param("tenant", tenantId)
                        .param("workOrder", workOrderId)
                        .param("event", sourceEventId)
                        .param(
                                "model",
                                assessment
                                        .map(CompletionCheck.Assessment::model)
                                        .orElse(null))
                        .param("version", CompletionCheck.PROMPT_VERSION)
                        .param(
                                "items",
                                json.writeValueAsString(assessment
                                        .map(CompletionCheck.Assessment::items)
                                        .orElse(List.of())))
                        .param(
                                "confidence",
                                assessment
                                        .map(a -> BigDecimal.valueOf(a.confidence()))
                                        .orElse(null))
                        .param("photos", photoCount)
                        .param("recommendation", decision.recommendation().name())
                        .param("reason", decision.reason())
                        .update()
                > 0;
    }

    /** Records the decision on an open audit; false if it was decided already. */
    @Transactional
    boolean decide(UUID id, State state, String decidedBy, @Nullable String reason) {
        return jdbc.sql("""
                        UPDATE completion_audit SET state = :state, decided_by = :by, decision_reason = :reason,
                                                    decided_at = now()
                        WHERE id = :id AND state = 'NEEDS_REVIEW'
                        """)
                        .param("id", id)
                        .param("state", state.name())
                        .param("by", decidedBy)
                        .param("reason", reason)
                        .update()
                > 0;
    }

    /** The work order was decided outside the review queue: close its open audit with what was decided. */
    @Transactional
    void closeOpen(UUID workOrderId, String outcome) {
        jdbc.sql("""
                        UPDATE completion_audit SET state = 'DECIDED_ELSEWHERE', decided_by = 'work order', decision_reason = :outcome,
                                                    decided_at = now()
                        WHERE work_order_id = :workOrder AND state = 'NEEDS_REVIEW'
                        """).param("workOrder", workOrderId).param("outcome", outcome).update();
    }

    @Transactional(readOnly = true)
    Optional<Audit> find(UUID id) {
        return jdbc.sql("SELECT * FROM completion_audit WHERE id = :id")
                .param("id", id)
                .query(this::row)
                .optional();
    }

    @Transactional(readOnly = true)
    Optional<Audit> latestFor(UUID workOrderId) {
        return jdbc.sql("SELECT * FROM completion_audit WHERE work_order_id = :id ORDER BY created_at DESC LIMIT 1")
                .param("id", workOrderId)
                .query(this::row)
                .optional();
    }

    /** The company's queue, oldest first (row-level security picks the company). */
    @Transactional(readOnly = true)
    List<Audit> queue(int limit) {
        return jdbc.sql("SELECT * FROM completion_audit WHERE state = 'NEEDS_REVIEW' ORDER BY created_at LIMIT :limit")
                .param("limit", limit)
                .query(this::row)
                .list();
    }

    private Audit row(ResultSet rs, int n) throws SQLException {
        var decidedAt = rs.getTimestamp("decided_at");
        return new Audit(
                rs.getObject("id", UUID.class),
                rs.getObject("work_order_id", UUID.class),
                State.valueOf(rs.getString("state")),
                rs.getString("model"),
                rs.getString("prompt_version"),
                json.readValue(rs.getString("items"), ITEMS),
                rs.getBigDecimal("confidence"),
                rs.getInt("photo_count"),
                AuditPolicy.Recommendation.valueOf(rs.getString("recommendation")),
                rs.getString("reason"),
                rs.getString("decided_by"),
                rs.getString("decision_reason"),
                decidedAt == null ? null : decidedAt.toInstant(),
                rs.getTimestamp("created_at").toInstant());
    }
}
