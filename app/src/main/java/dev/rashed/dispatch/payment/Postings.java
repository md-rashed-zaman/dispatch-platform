package dev.rashed.dispatch.payment;

import dev.rashed.dispatch.ledger.Ledger.Account;
import dev.rashed.dispatch.ledger.Ledger.Entry;
import dev.rashed.dispatch.ledger.Ledger.Kind;
import dev.rashed.dispatch.ledger.Ledger.Line;
import dev.rashed.dispatch.shared.Money;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Which accounts each money movement touches (docs/diagrams/payment-flow.md, "Ledger postings"). Pure functions:
 * the property test drives them with random sequences and checks that nothing is created or lost.
 */
final class Postings {

    /** The platform's share and the technician's, in integers: the remainder goes to the technician. */
    record Split(long platformFee, long technician) {

        static Split of(long budget, int feeBasisPoints) {
            if (budget < 0 || feeBasisPoints < 0 || feeBasisPoints > 10_000) {
                throw new IllegalArgumentException("budget " + budget + ", fee " + feeBasisPoints + " bp");
            }
            // Multiply first, then divide (rounding down): 10% of 4,999 poisha is 499, and 4,500 goes to the
            // technician.
            var fee = Math.multiplyExact(budget, (long) feeBasisPoints) / 10_000;
            return new Split(fee, budget - fee);
        }
    }

    /** The client's money arrives: owed to us by the gateway, held for the client. */
    static Entry capture(UUID tenant, UUID paymentId, UUID workOrderId, Money amount) {
        return new Entry(
                tenant,
                "capture:" + paymentId,
                Kind.CAPTURE,
                workOrderId,
                amount.currency(),
                "Client paid into escrow",
                List.of(
                        Line.debit(Account.GATEWAY_CLEARING, amount.minor()),
                        Line.credit(Account.CLIENT_ESCROW, amount.minor())));
    }

    /** The gateway keeps its charge: an expense of the platform, never the technician's. */
    static Optional<Entry> gatewayFee(UUID tenant, UUID paymentId, UUID workOrderId, Money amount, Money storeAmount) {
        var charge = amount.minor() - storeAmount.minor();
        if (charge <= 0 || !amount.currency().equals(storeAmount.currency())) {
            return Optional.empty();
        }
        return Optional.of(new Entry(
                tenant,
                "gateway-fee:" + paymentId,
                Kind.GATEWAY_FEE,
                workOrderId,
                amount.currency(),
                "Payment gateway charge",
                List.of(Line.debit(Account.GATEWAY_FEES, charge), Line.credit(Account.GATEWAY_CLEARING, charge))));
    }

    /** Work approved: escrow is released, the technician's share becomes owed to them, the fee is earned. */
    static Entry approval(UUID tenant, UUID workOrderId, UUID technician, Money escrow, int feeBasisPoints) {
        var split = Split.of(escrow.minor(), feeBasisPoints);
        var lines = new java.util.ArrayList<Line>();
        lines.add(Line.debit(Account.CLIENT_ESCROW, escrow.minor()));
        if (split.technician() > 0) {
            lines.add(
                    Line.credit(Account.TECHNICIAN_PAYABLE, split.technician()).forTechnician(technician));
        }
        if (split.platformFee() > 0) {
            lines.add(Line.credit(Account.PLATFORM_REVENUE, split.platformFee()));
        }
        return new Entry(
                tenant,
                "approval:" + workOrderId,
                Kind.APPROVAL,
                workOrderId,
                escrow.currency(),
                "Work approved: escrow released",
                lines);
    }

    /** The technician was paid from the platform's bank account. */
    static Entry payout(
            UUID tenant, UUID payoutId, UUID workOrderId, UUID technician, Money amount, String bankReference) {
        return new Entry(
                tenant,
                "payout:" + payoutId,
                Kind.PAYOUT,
                workOrderId,
                amount.currency(),
                "Paid to the technician (" + bankReference + ")",
                List.of(
                        Line.debit(Account.TECHNICIAN_PAYABLE, amount.minor()).forTechnician(technician),
                        Line.credit(Account.BANK, amount.minor())));
    }

    /** The client got their money back through the gateway: nothing is held for them any more. */
    static Entry refund(UUID tenant, UUID refundId, UUID workOrderId, Money amount) {
        return new Entry(
                tenant,
                "refund:" + refundId,
                Kind.REFUND,
                workOrderId,
                amount.currency(),
                "Refunded to the client",
                List.of(
                        Line.debit(Account.CLIENT_ESCROW, amount.minor()),
                        Line.credit(Account.GATEWAY_CLEARING, amount.minor())));
    }

    private Postings() {}
}
