package dev.rashed.dispatch.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.rashed.dispatch.TestcontainersConfiguration;
import dev.rashed.dispatch.ledger.Ledger.Account;
import dev.rashed.dispatch.ledger.Ledger.Entry;
import dev.rashed.dispatch.ledger.Ledger.Kind;
import dev.rashed.dispatch.ledger.Ledger.Line;
import dev.rashed.dispatch.tenancy.TenantContext;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** The ledger's rules, enforced by the code and again by the database (V20): balanced, append-only, isolated. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class LedgerTests {

    @Autowired
    Ledger ledger;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate transaction;

    final UUID tenant = UUID.randomUUID();
    final UUID workOrder = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        jdbc.update("insert into company (id, name, slug) values (?, 'Company', ?)", tenant, "company-" + tenant);
    }

    @Test
    void anEntryMovesMoneyBetweenAccountsAndPostingItTwiceChangesNothing() {
        var capture = entry(
                "capture:1",
                Line.debit(Account.GATEWAY_CLEARING, 500_000),
                Line.credit(Account.CLIENT_ESCROW, 500_000));

        assertThat(asTenant(() -> ledger.post(capture))).isTrue();
        assertThat(asTenant(() -> ledger.post(capture))).isFalse(); // a retried listener

        var balances = asTenant(ledger::balances);
        assertThat(balances)
                .containsEntry(Account.GATEWAY_CLEARING, 500_000L)
                .containsEntry(Account.CLIENT_ESCROW, 500_000L);
        assertThat(asTenant(() -> ledger.balance(Account.CLIENT_ESCROW, workOrder)))
                .isEqualTo(500_000L);
        assertThat(asTenant(() -> ledger.forWorkOrder(workOrder)))
                .singleElement()
                .satisfies(e -> assertThat(e.lines()).hasSize(2));
    }

    @Test
    void theCodeRefusesAnUnbalancedEntry() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> entry("x", Line.debit(Account.BANK, 100), Line.credit(Account.PLATFORM_REVENUE, 99)))
                .withMessageContaining("Unbalanced");
        assertThatIllegalArgumentException().isThrownBy(() -> new Line(Account.BANK, null, 100, 100));
        assertThatIllegalArgumentException().isThrownBy(() -> Line.debit(Account.BANK, 0));
    }

    @Test
    void theDatabaseRefusesAnUnbalancedEntryEvenWhenTheCodeIsSkipped() {
        var id = UUID.randomUUID();
        assertThatThrownBy(() -> asTenant(() -> {
                    jdbc.update("""
                            insert into journal_entry (id, tenant_id, reference, kind, currency, description)
                            values (?, ?, 'bad', 'CAPTURE', 'BDT', 'skipped the code')
                            """, id, tenant);
                    jdbc.update("""
                            insert into journal_line (entry_id, tenant_id, account_code, debit_minor)
                            values (?, ?, 'bank', 100)
                            """, id, tenant);
                    return null;
                }))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("unbalanced");
        assertThat(asTenant(() ->
                        jdbc.queryForObject("select count(*) from journal_entry where id = ?", Integer.class, id)))
                .isZero();
    }

    @Test
    void entriesCantBeChangedOnlyReversed() {
        asTenant(() -> ledger.post(entry(
                "approval:1",
                Line.debit(Account.CLIENT_ESCROW, 1_000),
                Line.credit(Account.TECHNICIAN_PAYABLE, 900),
                Line.credit(Account.PLATFORM_REVENUE, 100))));
        var posted = asTenant(() -> ledger.forWorkOrder(workOrder)).getFirst();

        assertThatThrownBy(() -> asTenant(() ->
                        jdbc.update("update journal_line set debit_minor = 2000 where entry_id = ?", posted.id())))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> asTenant(() -> jdbc.update("delete from journal_entry where id = ?", posted.id())))
                .isInstanceOf(DataAccessException.class);

        assertThat(asTenant(() -> ledger.reverse(posted.id(), "posted to the wrong job")))
                .isTrue();
        assertThat(asTenant(() -> ledger.reverse(posted.id(), "again"))).isFalse();

        var entries = asTenant(() -> ledger.forWorkOrder(workOrder));
        assertThat(entries).extracting(Ledger.PostedEntry::kind).containsExactly(Kind.CAPTURE, Kind.REVERSAL);
        assertThat(entries.getLast().reversesEntryId()).isEqualTo(posted.id());
        assertThat(asTenant(ledger::balances))
                .allSatisfy((account, balance) -> assertThat(balance).isZero());
    }

    @Test
    void aCompanySeesOnlyItsOwnLedgerButThePlatformTotalsSeeAll() {
        asTenant(() -> ledger.post(entry(
                "capture:2", Line.debit(Account.GATEWAY_CLEARING, 700), Line.credit(Account.CLIENT_ESCROW, 700))));
        var other = UUID.randomUUID();
        jdbc.update("insert into company (id, name, slug) values (?, 'Other', ?)", other, "company-" + other);

        var seenByOther = TenantContext.callAs(other, () -> transaction.execute(s -> ledger.balances()));
        assertThat(seenByOther)
                .allSatisfy((account, balance) -> assertThat(balance).isZero());
        var totals = transaction.execute(s -> ledger.platformTotals());
        long debits = totals.values().stream().mapToLong(t -> t[0]).sum();
        long credits = totals.values().stream().mapToLong(t -> t[1]).sum();
        assertThat(debits).isEqualTo(credits).isGreaterThanOrEqualTo(700);
    }

    private Entry entry(String reference, Line... lines) {
        return new Entry(tenant, reference, Kind.CAPTURE, workOrder, "BDT", "test", List.of(lines));
    }

    private <T> T asTenant(Supplier<T> work) {
        return TenantContext.callAs(tenant, () -> transaction.execute(s -> work.get()));
    }
}
