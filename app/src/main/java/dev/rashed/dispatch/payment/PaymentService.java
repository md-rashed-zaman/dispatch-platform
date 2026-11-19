package dev.rashed.dispatch.payment;

import dev.rashed.dispatch.identity.CurrentUser;
import dev.rashed.dispatch.ledger.Ledger;
import dev.rashed.dispatch.payment.PaymentProvider.Status;
import dev.rashed.dispatch.payment.Payments.Payment;
import dev.rashed.dispatch.payment.Payments.Settlement;
import dev.rashed.dispatch.payment.Payments.State;
import dev.rashed.dispatch.shared.BusinessRuleException;
import dev.rashed.dispatch.shared.DependencyUnavailableException;
import dev.rashed.dispatch.shared.Money;
import dev.rashed.dispatch.shared.NotFoundException;
import dev.rashed.dispatch.shared.Timestamps;
import dev.rashed.dispatch.shared.Uuids;
import dev.rashed.dispatch.tenancy.TenantContext;
import dev.rashed.dispatch.tenancy.TenantDirectory;
import dev.rashed.dispatch.workorder.WorkOrderFunding;
import dev.rashed.dispatch.workorder.WorkOrderStatus;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Funding a work order (ADR 0015). Provider calls never run inside a database transaction: a payment page can take
 * seconds, and a held connection would make the whole app wait with it.
 */
@Service
class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    /**
     * What the API returns. {@code redirectUrl} only while the customer can still pay on it; {@code refund} once
     * the money is being (or has been) returned.
     */
    record PaymentView(
            UUID id,
            UUID workOrderId,
            State status,
            Money amount,
            @Nullable URI redirectUrl,
            @Nullable String reason,
            Instant createdAt,
            @Nullable Instant settledAt,
            Refunds.@Nullable State refund) {

        static PaymentView of(Payment p) {
            return of(p, null);
        }

        static PaymentView of(Payment p, Refunds.@Nullable State refund) {
            return new PaymentView(
                    p.id(),
                    p.workOrderId(),
                    p.state(),
                    p.amount(),
                    p.state() == State.PENDING ? p.gatewayUrl() : null,
                    p.reason(),
                    p.createdAt(),
                    p.settledAt(),
                    refund);
        }
    }

    private final Payments payments;
    private final PaymentProvider provider;
    private final WorkOrderFunding workOrders;
    private final TenantDirectory tenants;
    private final PaymentProperties properties;
    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;
    private final MeterRegistry metrics;
    private final Ledger ledger;
    private final Refunding refunding;
    private final Refunds refunds;

    PaymentService(
            Payments payments,
            PaymentProvider provider,
            WorkOrderFunding workOrders,
            TenantDirectory tenants,
            PaymentProperties properties,
            JdbcClient jdbc,
            PlatformTransactionManager transactions,
            MeterRegistry metrics,
            Ledger ledger,
            Refunding refunding,
            Refunds refunds) {
        this.refunds = refunds;
        this.ledger = ledger;
        this.refunding = refunding;
        this.payments = payments;
        this.provider = provider;
        this.workOrders = workOrders;
        this.tenants = tenants;
        this.properties = properties;
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactions);
        this.metrics = metrics;
    }

    /**
     * Starts (or resumes) funding a work order: the answer carries the provider's payment page. Called as the
     * work order's company (row-level security: another company's work order is not found).
     *
     * <ul>
     *   <li>Its payment page is still open: the same page again, so a customer who cancelled can try another
     *       method on the same session, and two clicks never make two payments.
     *   <li>Another request is creating the page right now: 409, try again in a moment. Only the request that
     *       created the payment asks the provider for its page, so one payment never gets two.
     *   <li>An attempt never got a page (the app stopped mid-call, longer ago than {@code staleAfter}): nobody can
     *       pay it, so it's closed and a new attempt starts.
     * </ul>
     */
    PaymentView start(UUID workOrderId) {
        var tenant = TenantContext.current().orElseThrow(() -> new IllegalStateException("No company"));
        record Live(Payment payment, boolean created) {}
        var live = Objects.requireNonNull(transaction.execute(status -> {
            var workOrder = workOrders
                    .snapshot(workOrderId)
                    .orElseThrow(() -> new NotFoundException("Work order", workOrderId));
            if (workOrder.funded()) {
                throw new BusinessRuleException("already-funded", "This job is already funded.");
            }
            if (workOrder.status() != WorkOrderStatus.ASSIGNED && workOrder.status() != WorkOrderStatus.IN_PROGRESS) {
                throw new BusinessRuleException(
                        "not-fundable",
                        "A job can be funded once a technician has it, not while it's " + workOrder.status() + ".");
            }
            if (!workOrder.budget().isPositive()) {
                throw new BusinessRuleException("no-budget", "This job has no budget to pay.");
            }
            var existing = payments.live(workOrderId);
            if (existing.isPresent()
                    && existing.get().state() == State.INITIATED
                    && existing.get().createdAt().isBefore(Timestamps.now().minus(properties.staleAfter()))) {
                payments.settle(existing.get().id(), State.FAILED, "The payment page was never created.", null);
                existing = Optional.empty();
            }
            if (existing.isPresent()) {
                return new Live(existing.get(), false);
            }
            var createdBy = CurrentUser.current().map(CurrentUser::id).orElse(null);
            return payments.insert(Uuids.v7(), tenant, workOrderId, workOrder.budget(), createdBy)
                    .map(created -> new Live(created, true))
                    // Another request created one a moment ago: theirs it is.
                    .or(() -> payments.live(workOrderId).map(theirs -> new Live(theirs, false)))
                    .orElseThrow();
        }));
        var payment = live.payment();
        return switch (payment.state()) {
            case PENDING -> PaymentView.of(payment);
            case CAPTURED -> throw new BusinessRuleException("already-funded", "This job is already funded.");
            case INITIATED -> {
                if (!live.created()) {
                    throw new BusinessRuleException(
                            "payment-in-progress", "The payment page is being prepared. Try again in a moment.");
                }
                yield createSession(payment);
            }
            default -> throw new IllegalStateException("live payment in state " + payment.state());
        };
    }

    private PaymentView createSession(Payment payment) {
        var title = workOrders
                .snapshot(payment.workOrderId())
                .map(WorkOrderFunding.Snapshot::title)
                .orElse("Field service");
        // shortcut: the provider needs a customer name and email; a company without a notification address gets
        // the platform's. Collect a billing contact per company when clients need receipts from the provider.
        var contact = tenants.notificationContact(payment.tenantId())
                .orElseGet(() -> new TenantDirectory.NotificationContact("Dispatch client", "no-reply@dispatch.local"));
        var callbacks = properties.callbackBaseUrl().toString().replaceAll("/$", "") + "/api/payments/sslcommerz";
        PaymentProvider.Session session;
        try {
            session = provider.createSession(new PaymentProvider.Checkout(
                    payment.tranId(),
                    payment.amount(),
                    title.length() > 250 ? title.substring(0, 250) : title,
                    contact.companyName(),
                    contact.email(),
                    URI.create(callbacks + "/return"),
                    URI.create(callbacks + "/return"),
                    URI.create(callbacks + "/return"),
                    URI.create(callbacks + "/ipn")));
        } catch (PaymentProvider.Unavailable unknown) {
            // Maybe the provider made a session, maybe not: either way the customer never got its page, so nobody
            // can pay it, and closing it is safe. The client's retry starts a new attempt.
            log.warn("Payment {}: creating the payment page failed: {}", payment.id(), unknown.getMessage());
            settleAs(payment, State.FAILED, "The payment page couldn't be created.", null);
            count("session_unavailable");
            throw new DependencyUnavailableException("the payment provider", unknown);
        } catch (PaymentProvider.Refused refused) {
            log.error("Payment {}: the provider refused the payment page: {}", payment.id(), refused.getMessage());
            settleAs(payment, State.FAILED, "The payment provider refused: " + refused.getMessage(), null);
            count("session_refused");
            throw new BusinessRuleException(
                    "payment-refused", "The payment provider refused to start this payment. Try again later.");
        }
        var updated = transaction.execute(status -> {
            payments.pending(payment.id(), session.sessionKey(), session.gatewayUrl());
            return payments.find(payment.id()).orElseThrow();
        });
        count("session_created");
        return PaymentView.of(Objects.requireNonNull(updated));
    }

    /** The newest payment of a work order the caller can see. */
    Optional<PaymentView> latest(UUID workOrderId) {
        return transaction.execute(status -> {
            workOrders.snapshot(workOrderId).orElseThrow(() -> new NotFoundException("Work order", workOrderId));
            return payments.latest(workOrderId)
                    .map(p -> PaymentView.of(
                            p,
                            refunds.forPayment(p.id())
                                    .map(Refunds.Refund::state)
                                    .orElse(null)));
        });
    }

    /**
     * Settles a payment from the provider's own answer: the one way a payment is captured. The provider's notice
     * and the customer's return only name the payment ({@code tranId}) and maybe a validation id; neither is
     * believed. Safe to call any number of times, from anywhere, at once.
     *
     * @return the payment, or empty if we have no payment with this id (a made-up notice)
     */
    Optional<Payment> confirm(String tranId, @Nullable String valIdHint) {
        var tenant = tenantOf(tranId);
        if (tenant.isEmpty()) {
            log.warn("Payment notice for unknown tran_id {}: ignored", abbreviate(tranId));
            count("unknown_notice");
            return Optional.empty();
        }
        return TenantContext.callAs(tenant.get(), () -> {
            var payment =
                    transaction.execute(status -> payments.byTranId(tranId)).orElseThrow();
            if (payment.state() == State.CAPTURED) {
                return Optional.of(payment); // already settled: a repeated notice changes nothing
            }
            List<PaymentProvider.Transaction> answers;
            if (valIdHint != null && !valIdHint.isBlank()) {
                var validated = provider.validate(valIdHint);
                if (validated.isEmpty()) {
                    log.warn("Payment {}: validation id unknown to the provider (forged notice?)", payment.id());
                    count("forged_notice");
                    return Optional.of(payment);
                }
                answers = List.of(validated.get());
            } else {
                answers = provider.query(tranId);
            }
            apply(payment, answers);
            return transaction.execute(status -> payments.find(payment.id()));
        });
    }

    /**
     * Decides from the provider's transactions for this payment. A transaction for another tran_id, or paid with
     * another amount or currency, is never a capture: it's recorded for a person.
     */
    void apply(Payment payment, List<PaymentProvider.Transaction> answers) {
        var ours = answers.stream()
                .filter(t -> t.tranId().equals(payment.tranId()))
                .toList();
        if (ours.size() != answers.size()) {
            log.warn("Payment {}: the provider's answer names another tran_id", payment.id());
            recordMismatch(payment, "OTHER_TRAN_ID", "The provider's answer was about another transaction.");
            count("tran_id_mismatch");
        }
        var paid = ours.stream().filter(t -> t.status() == Status.PAID).toList();
        var paidRight =
                paid.stream().filter(t -> t.amount().equals(payment.amount())).findFirst();
        if (paidRight.isEmpty() && !paid.isEmpty()) {
            var t = paid.getFirst();
            log.error(
                    "Payment {}: paid {} {} but {} {} was asked",
                    payment.id(),
                    t.amount().minor(),
                    t.amount().currency(),
                    payment.amount().minor(),
                    payment.amount().currency());
            recordMismatch(
                    payment, t.providerStatus(), "Paid " + t.amount() + ", but " + payment.amount() + " was asked.");
            count("amount_mismatch");
            return;
        }
        if (paidRight.isPresent()) {
            var t = paidRight.get();
            if (!payment.state().isOpen()) {
                // We had given up on it (expired or failed), and the customer paid after all.
                recordMismatch(payment, t.providerStatus(), "Paid after Dispatch closed this payment: refund it.");
                count("late_capture");
                return;
            }
            if (t.riskLevel() > 0) {
                // SSLCommerz asks merchants to hold the service for a risky transaction until the customer is
                // verified: a person decides, the job stays unfunded meanwhile.
                transaction.executeWithoutResult(status -> payments.note(payment.id(), "Risk review"));
                recordMismatch(
                        payment,
                        t.providerStatus(),
                        "Flagged risky by the provider (risk_level " + t.riskLevel() + ").");
                count("risk_review");
                return;
            }
            settleAs(
                    payment,
                    State.CAPTURED,
                    null,
                    new Settlement(t.valId(), t.bankTranId(), t.providerStatus(), t.riskLevel(), t.storeAmount()));
            return;
        }
        if (!payment.state().isOpen()) {
            return;
        }
        var stillPossible = ours.stream().anyMatch(t -> t.status() == Status.PENDING);
        var pastLifetime = payment.createdAt().isBefore(Timestamps.now().minus(properties.sessionLifetime()));
        if (stillPossible || !pastLifetime) {
            return; // the customer may still pay on this page: wait
        }
        if (ours.isEmpty()) {
            settleAs(payment, State.EXPIRED, "Nobody paid before the payment page expired.", null);
        } else {
            var last = ours.getLast();
            settleAs(
                    payment,
                    State.FAILED,
                    "The payment didn't go through (" + last.providerStatus() + ").",
                    new Settlement(last.valId(), last.bankTranId(), last.providerStatus(), last.riskLevel(), null));
        }
    }

    /**
     * Settles a payment. A capture is recorded in the ledger in the same transaction (the money and the record of
     * it commit together), and so is the work order's funding. A capture for a job that was cancelled while the
     * client was paying is kept, since the money is real, and refunded at once.
     */
    private void settleAs(Payment payment, State to, @Nullable String reason, @Nullable Settlement settlement) {
        var refund = new java.util.concurrent.atomic.AtomicReference<UUID>();
        var settled = transaction.execute(status -> {
            if (!payments.settle(payment.id(), to, reason, settlement)) {
                return false; // settled by a concurrent confirmation: theirs stands
            }
            if (to == State.CAPTURED) {
                ledger.post(
                        Postings.capture(payment.tenantId(), payment.id(), payment.workOrderId(), payment.amount()));
                if (settlement != null && settlement.storeAmount() != null) {
                    Postings.gatewayFee(
                                    payment.tenantId(),
                                    payment.id(),
                                    payment.workOrderId(),
                                    payment.amount(),
                                    settlement.storeAmount())
                            .ifPresent(ledger::post);
                }
                if (!workOrders.markFunded(payment.workOrderId(), Timestamps.now())) {
                    payments.mismatch(
                            payment,
                            settlement == null ? "VALID" : settlement.providerStatus(),
                            "Paid for a job that can't be funded any more (cancelled while paying): refunding it.");
                    refunding
                            .request(payment, "The job was cancelled while the client was paying.")
                            .ifPresent(refund::set);
                }
            }
            return true;
        });
        if (Boolean.TRUE.equals(settled)) {
            log.info("Payment {} for work order {}: {}", payment.id(), payment.workOrderId(), to);
            count(to.name().toLowerCase());
        }
        if (refund.get() != null) {
            refunding.send(refund.get()); // best effort now; the reconciler retries
        }
    }

    private void recordMismatch(Payment payment, String providerStatus, String detail) {
        transaction.executeWithoutResult(status -> payments.mismatch(payment, providerStatus, detail));
    }

    /** Which work order a payment is for, by our tran_id; empty for an unknown id. */
    Optional<UUID> workOrderOf(String tranId) {
        return tenantOf(tranId)
                .flatMap(tenant -> TenantContext.callAs(
                        tenant,
                        () -> transaction
                                .execute(status -> payments.byTranId(tranId))
                                .map(Payment::workOrderId)));
    }

    private Optional<UUID> tenantOf(String tranId) {
        if (tranId == null || tranId.length() > 30) {
            return Optional.empty();
        }
        return Optional.ofNullable(transaction.execute(status -> jdbc.sql("SELECT payment_tenant(:tranId)")
                .param("tranId", tranId)
                .query(UUID.class)
                .optional()
                .orElse(null)));
    }

    private void count(String outcome) {
        metrics.counter("dispatch.payments", "outcome", outcome).increment();
    }

    private static String abbreviate(String text) {
        return text == null ? "null" : text.length() > 40 ? text.substring(0, 40) + "…" : text;
    }
}
