package dev.rashed.dispatch.payment;

import dev.rashed.dispatch.ledger.Ledger;
import dev.rashed.dispatch.payment.Payments.Payment;
import dev.rashed.dispatch.shared.Uuids;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Returning a captured payment to the client (cancelled before work started, or paid while it was being
 * cancelled). Three steps, each safe to repeat: record the request (with the change that caused it), ask the
 * provider (outside any transaction), and once the provider says it's done, post it to the ledger. Until then
 * the money is still in escrow, so the ledger never shows a refund that hasn't happened.
 */
@Component
class Refunding {

    private static final Logger log = LoggerFactory.getLogger(Refunding.class);

    private final Refunds refunds;
    private final Payments payments;
    private final PaymentProvider provider;
    private final Ledger ledger;
    private final TransactionTemplate transaction;
    private final MeterRegistry metrics;

    Refunding(
            Refunds refunds,
            Payments payments,
            PaymentProvider provider,
            Ledger ledger,
            PlatformTransactionManager transactions,
            MeterRegistry metrics) {
        this.refunds = refunds;
        this.payments = payments;
        this.provider = provider;
        this.ledger = ledger;
        this.transaction = new TransactionTemplate(transactions);
        this.metrics = metrics;
    }

    /** Records a full refund of a captured payment, in the caller's transaction; empty if it has one already. */
    @Transactional(propagation = Propagation.MANDATORY)
    Optional<UUID> request(Payment payment, String reason) {
        return refunds.request(Uuids.v7(), payment.tenantId(), payment.id(), payment.amount(), reason)
                .map(Refunds.Refund::id);
    }

    /**
     * Asks the provider to return the money (as the refund's company). If the provider can't be reached, the
     * refund stays REQUESTED and the reconciler asks again; if it refuses, a person has to return the money.
     */
    void send(UUID refundId) {
        var refund = transaction.execute(status -> refunds.find(refundId)).orElseThrow();
        if (refund.state() != Refunds.State.REQUESTED) {
            return;
        }
        var bankTranId = transaction
                .execute(status -> payments.bankTranId(refund.paymentId()))
                .orElse(null);
        if (bankTranId == null) {
            fail(refund, "The payment has no bank transaction id: refund it by hand.");
            return;
        }
        try {
            var started = provider.refund(bankTranId, refund.refundTransId(), refund.amount(), refund.reason());
            transaction.executeWithoutResult(status -> refunds.processing(refund.id(), started.refundRefId()));
            log.info("Refund {} of payment {} started at the provider", refund.id(), refund.paymentId());
            count("started");
        } catch (PaymentProvider.Unavailable unknown) {
            // Maybe started, maybe not: the retry carries the same refund_trans_id, and the provider answers
            // "processing" with the same reference if it had.
            log.warn("Refund {} not started yet: {}", refund.id(), unknown.getMessage());
            transaction.executeWithoutResult(status -> refunds.touch(refund.id()));
        } catch (PaymentProvider.Refused refused) {
            fail(refund, refused.getMessage());
        }
    }

    /** Asks the provider how a started refund is going; posts it to the ledger when it's done. */
    void check(Refunds.Refund refund) {
        if (refund.state() == Refunds.State.REQUESTED) {
            send(refund.id());
            return;
        }
        if (refund.state() != Refunds.State.PROCESSING || refund.refundRefId() == null) {
            return;
        }
        var state = provider.refundStatus(refund.refundRefId());
        switch (state) {
            case PROCESSING -> transaction.executeWithoutResult(status -> refunds.touch(refund.id()));
            case FAILED -> fail(refund, "The provider cancelled the refund.");
            case REFUNDED -> {
                var payment = transaction
                        .execute(status -> payments.find(refund.paymentId()))
                        .orElseThrow();
                var done = transaction.execute(status -> {
                    if (!refunds.settle(refund.id(), Refunds.State.REFUNDED, null)) {
                        return false;
                    }
                    ledger.post(
                            Postings.refund(refund.tenantId(), refund.id(), payment.workOrderId(), refund.amount()));
                    return true;
                });
                if (Boolean.TRUE.equals(done)) {
                    log.info("Refund {} of payment {} is done", refund.id(), refund.paymentId());
                    count("refunded");
                }
            }
        }
    }

    private void fail(Refunds.Refund refund, String why) {
        log.error(
                "Refund {} of payment {} failed: {}. Return the money by hand.", refund.id(), refund.paymentId(), why);
        transaction.executeWithoutResult(status -> {
            refunds.settle(refund.id(), Refunds.State.FAILED, why);
            payments.find(refund.paymentId())
                    .ifPresent(payment -> payments.mismatch(payment, "REFUND_FAILED", "Refund failed: " + why));
        });
        count("failed");
    }

    private void count(String outcome) {
        metrics.counter("dispatch.refunds", "outcome", outcome).increment();
    }
}
