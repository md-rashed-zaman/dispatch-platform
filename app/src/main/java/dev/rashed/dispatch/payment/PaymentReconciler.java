package dev.rashed.dispatch.payment;

import dev.rashed.dispatch.payment.PaymentProvider.Status;
import dev.rashed.dispatch.payment.Payments.State;
import dev.rashed.dispatch.shared.Timestamps;
import dev.rashed.dispatch.tenancy.TenantContext;
import dev.rashed.dispatch.tenancy.TenantDirectory;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Settles what the notices didn't (T33). One instance at a time (ShedLock); each step is also safe twice, because
 * every state change is conditional.
 *
 * <ul>
 *   <li>Every minute: payments open longer than {@code reconcile-after} (the notice got lost, the customer closed
 *       the tab) are settled from the provider's answer to a status query: captured, still pending, or failed or
 *       expired once the payment page's lifetime is over.
 *   <li>Every night: every payment that changed in the last day is compared with the provider; disagreements go to
 *       {@code payment_mismatch} for a person (a capture the provider doesn't know, a payment that arrived after we
 *       gave up). Old idempotency keys are deleted.
 * </ul>
 */
@Component
class PaymentReconciler {

    private static final Logger log = LoggerFactory.getLogger(PaymentReconciler.class);
    private static final int BATCH = 100;

    private final TenantDirectory tenants;
    private final Payments payments;
    private final PaymentService service;
    private final PaymentProvider provider;
    private final PaymentProperties properties;
    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;
    private final MeterRegistry metrics;

    PaymentReconciler(
            TenantDirectory tenants,
            Payments payments,
            PaymentService service,
            PaymentProvider provider,
            PaymentProperties properties,
            JdbcClient jdbc,
            PlatformTransactionManager transactions,
            MeterRegistry metrics) {
        this.tenants = tenants;
        this.payments = payments;
        this.service = service;
        this.provider = provider;
        this.properties = properties;
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactions);
        this.metrics = metrics;
    }

    @Scheduled(
            fixedDelayString = "${dispatch.payments.reconcile-interval:PT1M}",
            initialDelayString = "${dispatch.payments.reconcile-initial-delay:PT45S}")
    @SchedulerLock(
            name = "payment-reconciler",
            lockAtMostFor = "PT10M",
            lockAtLeastFor = "${dispatch.payments.reconcile-lock-at-least-for:PT20S}")
    public void settleOpen() {
        var checked = new AtomicInteger();
        var before = Timestamps.now().minus(properties.reconcileAfter());
        for (var tenant : tenants.allTenantIds()) {
            TenantContext.runAs(tenant, () -> {
                for (var payment : transaction.execute(status -> payments.openSince(before, BATCH))) {
                    try {
                        service.apply(payment, provider.query(payment.tranId()));
                        checked.incrementAndGet();
                    } catch (PaymentProvider.Unavailable | PaymentProvider.Refused cantAsk) {
                        // Still unknown: it stays open, and the next round asks again.
                        log.warn("Payment {} not reconciled yet: {}", payment.id(), cantAsk.getMessage());
                        return; // the provider is down for this tenant's whole batch too
                    }
                }
            });
        }
        if (checked.get() > 0) {
            log.info("Reconciled {} open payments with the provider", checked.get());
        }
    }

    /** Nightly, at 03:00 in Dhaka: compare the last day's payments with the provider. */
    @Scheduled(cron = "${dispatch.payments.daily-reconciliation:0 0 3 * * *}", zone = "Asia/Dhaka")
    @SchedulerLock(name = "payment-daily-reconciliation", lockAtMostFor = "PT1H", lockAtLeastFor = "PT1M")
    public void daily() {
        var compared = new AtomicInteger();
        var found = new AtomicInteger();
        var since = Timestamps.now().minus(Duration.ofHours(25)); // an hour of overlap: nothing falls between runs
        for (var tenant : tenants.allTenantIds()) {
            TenantContext.runAs(tenant, () -> {
                for (var payment : transaction.execute(status -> payments.changedSince(since))) {
                    if (payment.state() == State.INITIATED) {
                        continue; // never had a page: nothing at the provider to compare
                    }
                    try {
                        var answers = provider.query(payment.tranId()).stream()
                                .filter(t -> t.tranId().equals(payment.tranId()))
                                .toList();
                        compared.incrementAndGet();
                        var paid = answers.stream()
                                .filter(t ->
                                        t.status() == Status.PAID && t.amount().equals(payment.amount()))
                                .findFirst();
                        String disagreement = null;
                        String providerStatus = paid.map(PaymentProvider.Transaction::providerStatus)
                                .orElse(
                                        answers.isEmpty()
                                                ? "NONE"
                                                : answers.getLast().providerStatus());
                        if (payment.state() == State.CAPTURED && paid.isEmpty()) {
                            disagreement = "Captured here, but the provider has no matching payment.";
                        } else if ((payment.state() == State.FAILED || payment.state() == State.EXPIRED)
                                && paid.isPresent()) {
                            disagreement = "Paid at the provider after Dispatch closed this payment: refund it.";
                        }
                        if (disagreement != null) {
                            var message = disagreement;
                            if (Boolean.TRUE.equals(transaction.execute(
                                    status -> payments.mismatch(payment, providerStatus, message)))) {
                                found.incrementAndGet();
                                log.error("Payment {} disagrees with the provider: {}", payment.id(), message);
                            }
                        }
                    } catch (PaymentProvider.Unavailable | PaymentProvider.Refused cantAsk) {
                        log.warn(
                                "Daily reconciliation: payment {} not compared: {}",
                                payment.id(),
                                cantAsk.getMessage());
                    }
                }
                transaction.executeWithoutResult(
                        status -> jdbc.sql("DELETE FROM idempotency_key WHERE created_at < now() - interval '1 day'")
                                .update());
            });
        }
        metrics.counter("dispatch.payments.mismatches").increment(found.get());
        log.info("Daily payment reconciliation: {} compared, {} new mismatches", compared.get(), found.get());
    }
}
