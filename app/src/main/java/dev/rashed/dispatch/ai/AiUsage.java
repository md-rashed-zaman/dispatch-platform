package dev.rashed.dispatch.ai;

import dev.rashed.dispatch.tenancy.TenantContext;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A company's AI use and what it cost, from the {@code ai_call} audit (step 3.6, T32): the cost dashboard for a
 * company admin. Row-level security limits every query to the caller's company. Days are UTC days, the same days
 * the daily budget counts.
 *
 * <p>shortcut: embeddings aren't in {@code ai_call} (the backfill batches texts of many companies in one call),
 * so they aren't here; they cost about 1% of the rest and show in Grafana. Attribute per text if that changes.
 */
@Service
@PreAuthorize("hasRole('CLIENT_ADMIN')")
public class AiUsage {

    /** Calls that reached the provider: they count against the budget and cost money. */
    private static final String CALLED = "outcome IN ('OK', 'ERROR', 'INVALID_OUTPUT')";

    record Usage(LocalDate from, LocalDate to, Budget today, Totals totals, List<Day> days, List<Feature> features) {}

    record Budget(int calls, int limit) {}

    record Totals(int calls, int failed, int skipped, long inputTokens, long outputTokens, BigDecimal costUsd) {}

    record Day(LocalDate date, int calls, BigDecimal costUsd) {}

    /**
     * @param failed reached the provider but gave no usable answer (error or invalid output)
     * @param skipped not sent: rate limited, circuit open or over the daily budget; the feature used its fallback
     */
    record Feature(
            String feature,
            int calls,
            int failed,
            int skipped,
            long inputTokens,
            long outputTokens,
            BigDecimal costUsd) {}

    private final JdbcClient jdbc;
    private final AiProperties ai;

    AiUsage(JdbcClient jdbc, AiProperties ai) {
        this.jdbc = jdbc;
        this.ai = ai;
    }

    /** The last {@code days} UTC days, today included, with a row for every day (zero when unused). */
    @Transactional(readOnly = true)
    public Usage lastDays(int days) {
        var tenant = TenantContext.current().orElseThrow(() -> new IllegalStateException("No company"));
        var to = LocalDate.now(ZoneOffset.UTC);
        var from = to.minusDays(days - 1L);
        var since = Timestamp.from(from.atStartOfDay().toInstant(ZoneOffset.UTC));

        var features = jdbc.sql("""
                        SELECT feature,
                               count(*) FILTER (WHERE %1$s) AS calls,
                               count(*) FILTER (WHERE outcome IN ('ERROR', 'INVALID_OUTPUT')) AS failed,
                               count(*) FILTER (WHERE outcome IN ('RATE_LIMITED', 'CIRCUIT_OPEN', 'BUDGET_EXCEEDED'))
                                   AS skipped,
                               coalesce(sum(input_tokens) FILTER (WHERE %1$s), 0) AS input_tokens,
                               coalesce(sum(output_tokens) FILTER (WHERE %1$s), 0) AS output_tokens,
                               coalesce(sum(cost_usd), 0) AS cost_usd
                        FROM ai_call
                        WHERE tenant_id = :tenant AND created_at >= :since
                        GROUP BY feature
                        ORDER BY cost_usd DESC, feature
                        """.formatted(CALLED))
                .param("tenant", tenant)
                .param("since", since)
                .query((rs, row) -> new Feature(
                        rs.getString("feature"),
                        rs.getInt("calls"),
                        rs.getInt("failed"),
                        rs.getInt("skipped"),
                        rs.getLong("input_tokens"),
                        rs.getLong("output_tokens"),
                        rs.getBigDecimal("cost_usd")))
                .list();

        var used = new HashMap<LocalDate, Day>();
        jdbc.sql("""
                        SELECT (created_at AT TIME ZONE 'UTC')::date AS day,
                               count(*) FILTER (WHERE %s) AS calls,
                               coalesce(sum(cost_usd), 0) AS cost_usd
                        FROM ai_call
                        WHERE tenant_id = :tenant AND created_at >= :since
                        GROUP BY day
                        """.formatted(CALLED))
                .param("tenant", tenant)
                .param("since", since)
                .query((rs, row) ->
                        new Day(rs.getObject("day", LocalDate.class), rs.getInt("calls"), rs.getBigDecimal("cost_usd")))
                .list()
                .forEach(day -> used.put(day.date(), day));
        var series = new ArrayList<Day>();
        for (var day = from; !day.isAfter(to); day = day.plusDays(1)) {
            series.add(used.getOrDefault(day, new Day(day, 0, BigDecimal.ZERO)));
        }

        var totals = new Totals(
                features.stream().mapToInt(Feature::calls).sum(),
                features.stream().mapToInt(Feature::failed).sum(),
                features.stream().mapToInt(Feature::skipped).sum(),
                features.stream().mapToLong(Feature::inputTokens).sum(),
                features.stream().mapToLong(Feature::outputTokens).sum(),
                features.stream().map(Feature::costUsd).reduce(BigDecimal.ZERO, BigDecimal::add));
        var today = used.containsKey(to) ? used.get(to).calls() : 0;
        return new Usage(from, to, new Budget(today, ai.dailyCallsPerTenant()), totals, series, features);
    }
}
