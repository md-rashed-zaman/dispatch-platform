package dev.rashed.dispatch.ledger;

import dev.rashed.dispatch.shared.Uuids;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Posting and reading journal entries. Row-level security: post and read as the entry's company, inside the
 * transaction of the change that moves the money, so the money and the record of it commit together.
 */
@Service
public class Ledger {

    /** The chart of accounts (V20). Assets and expenses grow with debits; liabilities and revenue with credits. */
    public enum Account {
        BANK(true),
        GATEWAY_CLEARING(true),
        CLIENT_ESCROW(false),
        TECHNICIAN_PAYABLE(false),
        PLATFORM_REVENUE(false),
        GATEWAY_FEES(true);

        final boolean debitNormal;

        Account(boolean debitNormal) {
            this.debitNormal = debitNormal;
        }

        String code() {
            return name().toLowerCase();
        }

        static Account of(String code) {
            return valueOf(code.toUpperCase());
        }
    }

    public enum Kind {
        CAPTURE,
        GATEWAY_FEE,
        APPROVAL,
        PAYOUT,
        REFUND,
        REVERSAL
    }

    /** One side of an entry: exactly one of debit and credit is positive. */
    public record Line(Account account, @Nullable UUID technicianId, long debit, long credit) {

        public Line {
            Objects.requireNonNull(account, "account");
            if (debit < 0 || credit < 0 || (debit > 0) == (credit > 0)) {
                throw new IllegalArgumentException(
                        "A line is a positive debit or a positive credit: " + debit + "/" + credit);
            }
        }

        public static Line debit(Account account, long minor) {
            return new Line(account, null, minor, 0);
        }

        public static Line credit(Account account, long minor) {
            return new Line(account, null, 0, minor);
        }

        public Line forTechnician(UUID technician) {
            return new Line(account, technician, debit, credit);
        }

        Line mirrored() {
            return new Line(account, technicianId, credit, debit);
        }
    }

    /**
     * @param reference what caused it, unique per company ("capture:&lt;payment id&gt;"): posting it again changes
     *     nothing
     */
    public record Entry(
            UUID tenantId,
            String reference,
            Kind kind,
            @Nullable UUID workOrderId,
            String currency,
            String description,
            List<Line> lines) {

        public Entry {
            lines = List.copyOf(lines);
            if (lines.size() < 2) {
                throw new IllegalArgumentException("An entry needs at least two lines");
            }
            long debits = 0;
            long credits = 0;
            for (var line : lines) {
                debits = Math.addExact(debits, line.debit());
                credits = Math.addExact(credits, line.credit());
            }
            if (debits != credits) {
                throw new IllegalArgumentException(
                        "Unbalanced entry " + reference + ": debits " + debits + ", credits " + credits);
            }
        }
    }

    public record PostedEntry(
            UUID id,
            String reference,
            Kind kind,
            @Nullable UUID workOrderId,
            String currency,
            String description,
            @Nullable UUID reversesEntryId,
            Instant createdAt,
            List<Line> lines) {}

    private final JdbcClient jdbc;

    Ledger(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Posts an entry, in the caller's transaction.
     *
     * @return false if an entry with this reference was already posted (a retry): nothing changes
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean post(Entry entry) {
        return insert(entry, null);
    }

    /**
     * Cancels a wrong entry by posting its mirror (every debit becomes a credit), never by changing it.
     *
     * @return false if it was already reversed
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean reverse(UUID entryId, String why) {
        var original = entries("e.id = :id", Map.of("id", entryId)).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No journal entry " + entryId));
        var mirror = new Entry(
                tenantOf(entryId),
                "reversal:" + entryId,
                Kind.REVERSAL,
                original.workOrderId(),
                original.currency(),
                "Reverses \"" + original.description() + "\": " + why,
                original.lines().stream().map(Line::mirrored).toList());
        return insert(mirror, entryId);
    }

    /** The company's balance per account, in each account's normal direction (escrow held is positive). */
    @Transactional(readOnly = true)
    public Map<Account, Long> balances() {
        var balances = new EnumMap<Account, Long>(Account.class);
        for (var account : Account.values()) {
            balances.put(account, 0L);
        }
        jdbc.sql("""
                        SELECT account_code, sum(debit_minor) AS debits, sum(credit_minor) AS credits
                        FROM journal_line GROUP BY account_code
                        """)
                .query((rs, row) -> {
                    var account = Account.of(rs.getString("account_code"));
                    balances.put(account, normal(account, rs.getLong("debits"), rs.getLong("credits")));
                    return null;
                })
                .list();
        return balances;
    }

    /** One account's balance for one work order (escrow held for it, say). */
    @Transactional(readOnly = true)
    public long balance(Account account, UUID workOrderId) {
        return jdbc.sql("""
                        SELECT coalesce(sum(l.debit_minor), 0) AS debits, coalesce(sum(l.credit_minor), 0) AS credits
                        FROM journal_line l JOIN journal_entry e ON e.id = l.entry_id
                        WHERE e.work_order_id = :workOrder AND l.account_code = :account
                        """)
                .param("workOrder", workOrderId)
                .param("account", account.code())
                .query((rs, row) -> normal(account, rs.getLong("debits"), rs.getLong("credits")))
                .single();
    }

    /** A work order's entries, oldest first. */
    @Transactional(readOnly = true)
    public List<PostedEntry> forWorkOrder(UUID workOrderId) {
        return entries("e.work_order_id = :workOrder", Map.of("workOrder", workOrderId));
    }

    /**
     * Debits and credits per account across every company (a SECURITY DEFINER aggregate: sums only). Equal totals
     * mean no money was created or lost anywhere.
     */
    @Transactional(readOnly = true)
    public Map<Account, long[]> platformTotals() {
        var totals = new EnumMap<Account, long[]>(Account.class);
        jdbc.sql("SELECT account_code, debits, credits FROM ledger_totals()")
                .query((rs, row) -> totals.put(
                        Account.of(rs.getString("account_code")),
                        new long[] {rs.getLong("debits"), rs.getLong("credits")}))
                .list();
        return totals;
    }

    private boolean insert(Entry entry, @Nullable UUID reverses) {
        var id = Uuids.v7();
        var inserted = jdbc.sql("""
                        INSERT INTO journal_entry (id, tenant_id, reference, kind, work_order_id, currency, description,
                                                   reverses_entry_id)
                        VALUES (:id, :tenant, :reference, :kind, :workOrder, :currency, :description, :reverses)
                        ON CONFLICT (tenant_id, reference) DO NOTHING
                        """)
                .param("id", id)
                .param("tenant", entry.tenantId())
                .param("reference", entry.reference())
                .param("kind", entry.kind().name())
                .param("workOrder", entry.workOrderId())
                .param("currency", entry.currency())
                .param("description", entry.description())
                .param("reverses", reverses)
                .update();
        if (inserted == 0) {
            return false;
        }
        for (var line : entry.lines()) {
            jdbc.sql("""
                            INSERT INTO journal_line (entry_id, tenant_id, account_code, technician_id, debit_minor, credit_minor)
                            VALUES (:entry, :tenant, :account, :technician, :debit, :credit)
                            """)
                    .param("entry", id)
                    .param("tenant", entry.tenantId())
                    .param("account", line.account().code())
                    .param("technician", line.technicianId())
                    .param("debit", line.debit())
                    .param("credit", line.credit())
                    .update();
        }
        return true;
    }

    private UUID tenantOf(UUID entryId) {
        return jdbc.sql("SELECT tenant_id FROM journal_entry WHERE id = :id")
                .param("id", entryId)
                .query(UUID.class)
                .single();
    }

    private List<PostedEntry> entries(String condition, Map<String, Object> params) {
        var spec = jdbc.sql("""
                SELECT e.id, e.reference, e.kind, e.work_order_id, e.currency, e.description, e.reverses_entry_id,
                       e.created_at, l.account_code, l.technician_id, l.debit_minor, l.credit_minor
                FROM journal_entry e JOIN journal_line l ON l.entry_id = e.id
                """ + "WHERE " + condition + " ORDER BY e.created_at, e.id, l.id");
        for (var param : params.entrySet()) {
            spec = spec.param(param.getKey(), param.getValue());
        }
        var byId = new java.util.LinkedHashMap<UUID, PostedEntry>();
        var lines = new java.util.LinkedHashMap<UUID, List<Line>>();
        spec.query((rs, row) -> {
                    var id = rs.getObject("id", UUID.class);
                    lines.computeIfAbsent(id, k -> new ArrayList<>())
                            .add(new Line(
                                    Account.of(rs.getString("account_code")),
                                    rs.getObject("technician_id", UUID.class),
                                    rs.getLong("debit_minor"),
                                    rs.getLong("credit_minor")));
                    byId.putIfAbsent(
                            id,
                            new PostedEntry(
                                    id,
                                    rs.getString("reference"),
                                    Kind.valueOf(rs.getString("kind")),
                                    rs.getObject("work_order_id", UUID.class),
                                    rs.getString("currency"),
                                    rs.getString("description"),
                                    rs.getObject("reverses_entry_id", UUID.class),
                                    rs.getTimestamp("created_at").toInstant(),
                                    List.of()));
                    return null;
                })
                .list();
        return byId.values().stream()
                .map(e -> new PostedEntry(
                        e.id(),
                        e.reference(),
                        e.kind(),
                        e.workOrderId(),
                        e.currency(),
                        e.description(),
                        e.reversesEntryId(),
                        e.createdAt(),
                        List.copyOf(lines.get(e.id()))))
                .toList();
    }

    private static long normal(Account account, long debits, long credits) {
        return account.debitNormal ? debits - credits : credits - debits;
    }
}
