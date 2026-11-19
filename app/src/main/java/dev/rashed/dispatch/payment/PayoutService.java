package dev.rashed.dispatch.payment;

import dev.rashed.dispatch.identity.CurrentUser;
import dev.rashed.dispatch.ledger.Ledger;
import dev.rashed.dispatch.shared.BusinessRuleException;
import dev.rashed.dispatch.shared.NotFoundException;
import dev.rashed.dispatch.tenancy.TenantContext;
import dev.rashed.dispatch.tenancy.TenantDirectory;
import dev.rashed.dispatch.workorder.WorkOrderFunding;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Paying technicians what approved jobs owe them. SSLCommerz takes payments but can't pay out, so the platform
 * pays from its bank account (a bank transfer or bKash disbursement) and confirms each payout here with the
 * bank's reference: the payout is recorded, the ledger moves the money out of technician_payable, and the work
 * order becomes PAID, in one transaction.
 *
 * <p>shortcut: one payout per approved job, confirmed by hand. Batch them per technician (a weekly bank file)
 * behind a PayoutProvider when there are more than a person can type in.
 */
@Service
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
public class PayoutService {

    private static final Logger log = LoggerFactory.getLogger(PayoutService.class);

    public record PayoutView(
            UUID id,
            UUID workOrderId,
            UUID technicianId,
            String company,
            dev.rashed.dispatch.shared.Money amount,
            String status,
            String bankReference,
            java.time.Instant createdAt,
            java.time.Instant paidAt) {}

    private final Payouts payouts;
    private final Ledger ledger;
    private final WorkOrderFunding workOrders;
    private final TenantDirectory tenants;
    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;

    PayoutService(
            Payouts payouts,
            Ledger ledger,
            WorkOrderFunding workOrders,
            TenantDirectory tenants,
            JdbcClient jdbc,
            PlatformTransactionManager transactions) {
        this.payouts = payouts;
        this.ledger = ledger;
        this.workOrders = workOrders;
        this.tenants = tenants;
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactions);
    }

    /** Every company's unpaid payouts, oldest first. */
    public List<PayoutView> pending() {
        var all = new ArrayList<PayoutView>();
        for (var tenant : tenants.allTenantIds()) {
            var company = tenants.notificationContact(tenant)
                    .map(TenantDirectory.NotificationContact::companyName)
                    .orElse("");
            TenantContext.runAs(
                    tenant,
                    () -> transaction.execute(status -> payouts.pending()).forEach(p -> all.add(view(p, company))));
        }
        all.sort(java.util.Comparator.comparing(PayoutView::createdAt));
        return all;
    }

    /**
     * Records that the technician was paid. Repeating it with the same reference returns the payout unchanged;
     * a different reference for a paid payout is a conflict (someone paid it already, maybe twice: check).
     */
    public PayoutView confirm(UUID payoutId, String bankReference) {
        var tenant = Objects.requireNonNull(transaction.execute(status -> jdbc.sql("SELECT payout_tenant(:id)")
                        .param("id", payoutId)
                        .query(UUID.class)
                        .optional()))
                .orElseThrow(() -> new NotFoundException("Payout", payoutId));
        var by = CurrentUser.current().map(CurrentUser::id).orElse(null);
        return TenantContext.callAs(
                tenant,
                () -> transaction.execute(status -> {
                    var payout = payouts.find(payoutId).orElseThrow(() -> new NotFoundException("Payout", payoutId));
                    if (payouts.paid(payoutId, bankReference, by)) {
                        ledger.post(Postings.payout(
                                tenant,
                                payout.id(),
                                payout.workOrderId(),
                                payout.technicianId(),
                                payout.amount(),
                                bankReference));
                        workOrders.markPaid(payout.workOrderId());
                        log.info(
                                "Payout {} of {} to technician {} confirmed ({})",
                                payout.id(),
                                payout.amount().minor(),
                                payout.technicianId(),
                                bankReference);
                    } else if (!bankReference.equals(payout.bankReference())) {
                        throw new BusinessRuleException(
                                "already-paid",
                                "This payout was already confirmed with reference " + payout.bankReference() + ".");
                    }
                    return view(payouts.find(payoutId).orElseThrow(), "");
                }));
    }

    private static PayoutView view(Payouts.Payout p, String company) {
        return new PayoutView(
                p.id(),
                p.workOrderId(),
                p.technicianId(),
                company,
                p.amount(),
                p.status(),
                p.bankReference(),
                p.createdAt(),
                p.paidAt());
    }
}
