package dev.rashed.dispatch.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import dev.rashed.dispatch.ledger.Ledger.Account;
import dev.rashed.dispatch.ledger.Ledger.Line;
import dev.rashed.dispatch.shared.Money;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PostingsTest {

    @Test
    void theFeeRoundsDownAndTheTechnicianGetsTheRest() {
        assertThat(Postings.Split.of(499_900, 1000)).isEqualTo(new Postings.Split(49_990, 449_910));
        assertThat(Postings.Split.of(4_999, 1000)).isEqualTo(new Postings.Split(499, 4_500));
        assertThat(Postings.Split.of(1, 1000)).isEqualTo(new Postings.Split(0, 1));
        assertThat(Postings.Split.of(500_000, 0)).isEqualTo(new Postings.Split(0, 500_000));
        assertThat(Postings.Split.of(500_000, 10_000)).isEqualTo(new Postings.Split(500_000, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> Postings.Split.of(100, 10_001));
    }

    @Test
    void thePartsAlwaysAddUpToTheBudget() {
        var random = new Random(42);
        for (int i = 0; i < 10_000; i++) {
            long budget = random.nextLong(0, 10_000_000_000L);
            int fee = random.nextInt(0, 10_001);
            var split = Postings.Split.of(budget, fee);
            assertThat(split.platformFee() + split.technician()).isEqualTo(budget);
            assertThat(split.platformFee()).isBetween(0L, budget);
        }
    }

    @Test
    void anApprovalWithoutAFeeHasNoRevenueLine() {
        var entry = Postings.approval(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Money.bdt(1), 1000);

        assertThat(entry.lines())
                .extracting(Line::account)
                .containsExactly(Account.CLIENT_ESCROW, Account.TECHNICIAN_PAYABLE);
    }

    @Test
    void noGatewayFeeEntryWhenTheGatewayKeptNothing() {
        var payment = UUID.randomUUID();
        assertThat(Postings.gatewayFee(UUID.randomUUID(), payment, UUID.randomUUID(), Money.bdt(100), Money.bdt(100)))
                .isEmpty();
        assertThat(Postings.gatewayFee(
                        UUID.randomUUID(), payment, UUID.randomUUID(), Money.bdt(10_000), Money.bdt(9_750)))
                .hasValueSatisfying(e -> assertThat(e.lines())
                        .extracting(Line::debit, Line::credit)
                        .containsExactly(
                                org.assertj.core.groups.Tuple.tuple(250L, 0L),
                                org.assertj.core.groups.Tuple.tuple(0L, 250L)));
    }
}
