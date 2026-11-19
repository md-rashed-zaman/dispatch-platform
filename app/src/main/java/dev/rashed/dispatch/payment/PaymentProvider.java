package dev.rashed.dispatch.payment;

import dev.rashed.dispatch.shared.Money;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * A hosted-checkout payment provider (SSLCommerz today). Everything provider-specific stays behind this: field
 * names, decimal amounts, status words.
 *
 * <p>Failures come in two kinds. {@link Unavailable}: we don't know what happened (timeout, 5xx, connection
 * refused), so nothing may be concluded and the call is retried or resolved later by a query. {@link Refused}: the
 * provider answered and said no (bad credentials, invalid request).
 */
interface PaymentProvider {

    Session createSession(Checkout checkout);

    /** The transaction behind a validation id; empty when the provider doesn't know the id (a forged notice). */
    Optional<Transaction> validate(String valId);

    /** Every transaction the provider has for our transaction id: one per attempt in the session. */
    List<Transaction> query(String tranId);

    record Checkout(
            String tranId,
            Money amount,
            String productName,
            String customerName,
            String customerEmail,
            URI successUrl,
            URI failUrl,
            URI cancelUrl,
            URI ipnUrl) {}

    record Session(String sessionKey, URI gatewayUrl) {}

    /**
     * What the provider says about one transaction, already checked for shape. {@code amount} is what the customer
     * was asked to pay, in the currency we sent.
     */
    record Transaction(
            String tranId,
            @Nullable String valId,
            Status status,
            Money amount,
            @Nullable String bankTranId,
            int riskLevel,
            String providerStatus) {}

    enum Status {
        /** Paid. SSLCommerz: VALID, or VALIDATED (paid, asked about before). */
        PAID,
        /** Started, not finished. */
        PENDING,
        /** Declined, cancelled, or not attempted. */
        FAILED
    }

    final class Unavailable extends RuntimeException {
        Unavailable(String message, @Nullable Throwable cause) {
            super(message, cause);
        }
    }

    final class Refused extends RuntimeException {
        Refused(String message) {
            super(message);
        }
    }
}
