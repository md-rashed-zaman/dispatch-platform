package dev.rashed.dispatch.ai;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The {@code ai_call} audit table. Each write is its own short transaction: the gateway never holds a database
 * connection while it waits seconds for a model, and an attempt is recorded even if the caller's work later
 * fails. Row-level security applies: the tenant comes from the transaction, like every tenant-owned write.
 */
@Component
class AiCallLog {

    enum Outcome {
        OK,
        ERROR,
        INVALID_OUTPUT,
        REPLAYED,
        RATE_LIMITED,
        CIRCUIT_OPEN,
        BUDGET_EXCEEDED
    }

    record Entry(
            UUID tenantId,
            String feature,
            String promptVersion,
            String model,
            Outcome outcome,
            String inputRedacted,
            String output,
            String error,
            Integer inputTokens,
            Integer outputTokens,
            long latencyMillis) {}

    private final JdbcClient jdbc;
    private final TransactionTemplate ownTransaction;

    AiCallLog(JdbcClient jdbc, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.ownTransaction = new TransactionTemplate(transactions);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    void record(Entry entry) {
        ownTransaction.executeWithoutResult(status -> jdbc.sql("""
                        INSERT INTO ai_call (tenant_id, feature, prompt_version, model, outcome, input_redacted, output,
                                             error, input_tokens, output_tokens, latency_ms)
                        VALUES (:tenant, :feature, :version, :model, :outcome, :input, :output,
                                :error, :inputTokens, :outputTokens, :latency)
                        """)
                .param("tenant", entry.tenantId())
                .param("feature", entry.feature())
                .param("version", entry.promptVersion())
                .param("model", entry.model())
                .param("outcome", entry.outcome().name())
                .param("input", entry.inputRedacted())
                .param("output", entry.output())
                .param("error", entry.error())
                .param("inputTokens", entry.inputTokens())
                .param("outputTokens", entry.outputTokens())
                .param("latency", Math.toIntExact(Math.min(entry.latencyMillis(), Integer.MAX_VALUE)))
                .update());
    }

    /** The tenant's calls that reached the provider today (UTC), for its daily budget. */
    int providerCallsToday(UUID tenantId) {
        var midnight = LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC);
        return ownTransaction.execute(status -> jdbc.sql("""
                        SELECT count(*) FROM ai_call
                        WHERE tenant_id = :tenant AND created_at >= :midnight
                          AND outcome IN ('OK', 'ERROR', 'INVALID_OUTPUT')
                        """)
                .param("tenant", tenantId)
                .param("midnight", java.sql.Timestamp.from(midnight))
                .query(Integer.class)
                .single());
    }
}
