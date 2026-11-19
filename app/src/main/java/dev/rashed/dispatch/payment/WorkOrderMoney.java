package dev.rashed.dispatch.payment;

import dev.rashed.dispatch.ledger.Ledger;
import dev.rashed.dispatch.shared.Money;
import dev.rashed.dispatch.shared.Uuids;
import dev.rashed.dispatch.tenancy.TenantContext;
import dev.rashed.dispatch.workorder.WorkOrderStatus;
import dev.rashed.dispatch.workorder.WorkOrderStatusChanged;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What a work order's lifecycle does to its money (docs/diagrams/payment-flow.md):
 *
 * <ul>
 *   <li>APPROVED: the escrow is released, the technician's share (budget minus the platform fee) becomes owed to
 *       them, and a payout waits for the platform to pay it;
 *   <li>CANCELLED: a captured payment is refunded in full (cancelling isn't allowed once work has started).
 * </ul>
 *
 * After the work order's transaction commits, on another thread. The event is in the publication registry until
 * this returns, so a failure is redelivered; every step is idempotent (unique references), so a redelivery
 * changes nothing that was already done.
 */
@Component
class WorkOrderMoney {

    private static final Logger log = LoggerFactory.getLogger(WorkOrderMoney.class);

    private final Payments payments;
    private final Payouts payouts;
    private final Refunding refunding;
    private final Ledger ledger;
    private final PaymentProperties properties;
    private final TransactionTemplate transaction;

    WorkOrderMoney(
            Payments payments,
            Payouts payouts,
            Refunding refunding,
            Ledger ledger,
            PaymentProperties properties,
            PlatformTransactionManager transactions) {
        this.payments = payments;
        this.payouts = payouts;
        this.refunding = refunding;
        this.ledger = ledger;
        this.properties = properties;
        this.transaction = new TransactionTemplate(transactions);
    }

    // Not @ApplicationModuleListener: its transaction would begin before we name the tenant, and row-level
    // security would hide everything (same as the matching router).
    @Async
    @TransactionalEventListener
    void on(WorkOrderStatusChanged event) {
        if (event.to() == WorkOrderStatus.APPROVED) {
            TenantContext.runAs(event.tenantId(), () -> released(event));
        } else if (event.to() == WorkOrderStatus.CANCELLED) {
            TenantContext.runAs(event.tenantId(), () -> cancelled(event));
        }
    }

    private void released(WorkOrderStatusChanged event) {
        transaction.executeWithoutResult(status -> {
            var captured = payments.captured(event.workOrderId());
            if (captured.isEmpty()) {
                return; // never paid through Dispatch (payments optional): no money to move
            }
            var escrow = captured.get().amount();
            var posted = ledger.post(Postings.approval(
                    event.tenantId(),
                    event.workOrderId(),
                    event.technicianId(),
                    escrow,
                    properties.platformFeeBasisPoints()));
            var split = Postings.Split.of(escrow.minor(), properties.platformFeeBasisPoints());
            if (split.technician() > 0) {
                payouts.create(
                        Uuids.v7(),
                        event.tenantId(),
                        event.workOrderId(),
                        event.technicianId(),
                        new Money(split.technician(), escrow.currency()));
            }
            if (posted) {
                log.info(
                        "Work order {} approved: escrow {} released, {} owed to the technician",
                        event.workOrderId(),
                        escrow.minor(),
                        split.technician());
            }
        });
    }

    private void cancelled(WorkOrderStatusChanged event) {
        var refund = new AtomicReference<UUID>();
        transaction.executeWithoutResult(status -> payments.captured(event.workOrderId())
                .flatMap(payment -> refunding.request(payment, "The job was cancelled before work started."))
                .ifPresent(refund::set));
        if (refund.get() != null) {
            refunding.send(refund.get());
        }
    }
}
