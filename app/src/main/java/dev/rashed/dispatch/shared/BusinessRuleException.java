package dev.rashed.dispatch.shared;

import java.util.Map;

/**
 * The request is valid, but the current state of the data doesn't allow it (HTTP 409): approving a
 * cancelled work order, editing one that is already published. The caller should reload and decide again.
 */
public class BusinessRuleException extends IllegalStateException {

    public BusinessRuleException(String message) {
        super(message);
    }

    /** Stable kind of problem that clients can switch on, like {@code invalid-transition}. */
    public String type() {
        return "business-rule";
    }

    /** Extra machine-readable details for the error response. */
    public Map<String, Object> details() {
        return Map.of();
    }
}
