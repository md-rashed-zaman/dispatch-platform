package dev.rashed.dispatch.workorder;

import dev.rashed.dispatch.shared.BusinessRuleException;
import java.util.UUID;

/** The offer expired or was withdrawn before the technician accepted it. */
class OfferClosedException extends BusinessRuleException {

    OfferClosedException(UUID offerId) {
        super("Offer " + offerId + " is no longer open.");
    }

    @Override
    public String type() {
        return "offer-closed";
    }
}
