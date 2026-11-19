package dev.rashed.dispatch.payment;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rashed.dispatch.TestTechnicians;
import dev.rashed.dispatch.TestcontainersConfiguration;
import dev.rashed.dispatch.ledger.Ledger;
import dev.rashed.dispatch.ledger.Ledger.Account;
import dev.rashed.dispatch.shared.Money;
import dev.rashed.dispatch.tenancy.TenantContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Money is never created or lost (step 4.3): random sequences of everything that moves money (capture, the
 * gateway's charge, approval, payout, refund, and repeats of each) posted to the real ledger, checking after each
 * step that the platform balances, each job's escrow is all or nothing, and nothing owed goes negative. Seeded:
 * a failure names its seed, and the same seed replays it.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class LedgerPropertyTests {

    static final int SEQUENCES = 25;
    static final int STEPS = 50;

    @Autowired
    Ledger ledger;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate transaction;

    /** A job's money so far, as the flow allows it: what may happen next depends on what happened. */
    static final class Job {
        final UUID workOrder = UUID.randomUUID();
        final UUID payment = UUID.randomUUID();
        final UUID technician;
        final Money budget;
        boolean captured;
        boolean feeTaken;
        boolean approved;
        boolean paidOut;
        boolean refunded;

        Job(UUID technician, Money budget) {
            this.technician = technician;
            this.budget = budget;
        }
    }

    @Test
    void randomSequencesNeverCreateOrLoseMoney() {
        var technician = TestTechnicians.verified(jdbc, 23.79, 90.41, "cctv");
        for (int seed = 0; seed < SEQUENCES; seed++) {
            run(seed, technician);
        }
    }

    private void run(long seed, UUID technician) {
        var random = new Random(seed);
        var tenant = UUID.randomUUID();
        jdbc.update("insert into company (id, name, slug) values (?, 'Company', ?)", tenant, "company-" + tenant);
        var fee = random.nextInt(0, 3_001); // 0 to 30%
        var jobs = new ArrayList<Job>();
        var posted = new ArrayList<Ledger.Entry>();

        for (int step = 0; step < STEPS; step++) {
            if (jobs.isEmpty() || random.nextInt(4) == 0) {
                jobs.add(new Job(technician, Money.bdt(random.nextLong(1, 5_000_000))));
            }
            var job = jobs.get(random.nextInt(jobs.size()));
            var entry = next(random, tenant, job, fee);
            if (entry == null && !posted.isEmpty()) {
                entry = posted.get(random.nextInt(posted.size())); // a retry: must change nothing
            }
            if (entry == null) {
                continue;
            }
            var e = entry;
            var isNew = asTenant(tenant, () -> ledger.post(e));
            if (isNew) {
                posted.add(e);
            }
            check(seed, step, tenant, jobs, fee);
        }
    }

    /** One possible next movement for this job, or null if nothing can happen to it now. */
    private Ledger.Entry next(Random random, UUID tenant, Job job, int fee) {
        var options = new ArrayList<Runnable>();
        var chosen = new Ledger.Entry[1];
        if (!job.captured) {
            options.add(() -> {
                job.captured = true;
                chosen[0] = Postings.capture(tenant, job.payment, job.workOrder, job.budget);
            });
        }
        if (job.captured && !job.feeTaken) {
            options.add(() -> Postings.gatewayFee(
                            tenant,
                            job.payment,
                            job.workOrder,
                            job.budget,
                            new Money(job.budget.minor() - job.budget.minor() / 40, "BDT"))
                    .ifPresent(e -> {
                        job.feeTaken = true;
                        chosen[0] = e;
                    }));
        }
        if (job.captured && !job.approved && !job.refunded) {
            options.add(() -> {
                job.approved = true;
                chosen[0] = Postings.approval(tenant, job.workOrder, job.technician, job.budget, fee);
            });
            options.add(() -> {
                job.refunded = true;
                chosen[0] = Postings.refund(tenant, job.payment, job.workOrder, job.budget);
            });
        }
        var share = Postings.Split.of(job.budget.minor(), fee).technician();
        if (job.approved && !job.paidOut && share > 0) {
            options.add(() -> {
                job.paidOut = true;
                chosen[0] = Postings.payout(
                        tenant,
                        job.payment,
                        job.workOrder,
                        job.technician,
                        new Money(share, "BDT"),
                        "BANK-" + job.payment);
            });
        }
        if (options.isEmpty()) {
            return null;
        }
        options.get(random.nextInt(options.size())).run();
        return chosen[0];
    }

    private void check(long seed, int step, UUID tenant, List<Job> jobs, int fee) {
        var where = "seed " + seed + ", step " + step;
        var totals = transaction.execute(s -> ledger.platformTotals());
        long debits = totals.values().stream().mapToLong(t -> t[0]).sum();
        long credits = totals.values().stream().mapToLong(t -> t[1]).sum();
        assertThat(debits).as("platform debits equal credits, " + where).isEqualTo(credits);

        var balances = asTenant(tenant, ledger::balances);
        // The accounting equation: what we have (assets) plus what we spent (expenses) equals what we owe
        // (liabilities) plus what we earned (revenue).
        long left = balances.get(Account.BANK)
                + balances.get(Account.GATEWAY_CLEARING)
                + balances.get(Account.GATEWAY_FEES);
        long right = balances.get(Account.CLIENT_ESCROW)
                + balances.get(Account.TECHNICIAN_PAYABLE)
                + balances.get(Account.PLATFORM_REVENUE);
        assertThat(left)
                .as("assets + expenses = liabilities + revenue, " + where)
                .isEqualTo(right);
        assertThat(balances.get(Account.TECHNICIAN_PAYABLE))
                .as("owed to technicians, " + where)
                .isNotNegative();

        long expectedEscrow = 0;
        long expectedPayable = 0;
        for (var job : jobs) {
            var held = job.captured && !job.approved && !job.refunded ? job.budget.minor() : 0;
            expectedEscrow += held;
            assertThat(asTenant(tenant, () -> ledger.balance(Account.CLIENT_ESCROW, job.workOrder)))
                    .as("escrow for one job is all or nothing, " + where)
                    .isEqualTo(held);
            if (job.approved && !job.paidOut) {
                expectedPayable += Postings.Split.of(job.budget.minor(), fee).technician();
            }
        }
        assertThat(balances.get(Account.CLIENT_ESCROW)).as("escrow, " + where).isEqualTo(expectedEscrow);
        assertThat(balances.get(Account.TECHNICIAN_PAYABLE))
                .as("payable, " + where)
                .isEqualTo(expectedPayable);
    }

    private <T> T asTenant(UUID tenant, Supplier<T> work) {
        return TenantContext.callAs(tenant, () -> transaction.execute(s -> work.get()));
    }
}
