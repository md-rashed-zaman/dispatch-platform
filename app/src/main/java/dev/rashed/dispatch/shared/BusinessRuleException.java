package dev.rashed.dispatch.shared;

import java.util.Map;

/**
 * The request is valid, but the current state of the data doesn't allow it (HTTP 409): approving a
 * cancelled work order, editing one that is already published. The caller should reload and decide again.
 */
public class BusinessRuleException extends IllegalStateException {

    private final String type;

    public BusinessRuleException(String message) {
        this("business-rule", message);
    }

    /** @param type stable kind of problem that clients can switch on, like {@code not-funded} */
    public BusinessRuleException(String type, String message) {
        super(message);
        this.type = type;
    }

    /** Stable kind of problem that clients can switch on, like {@code invalid-transition}. */
    public String type() {
        return type;
    }

    /** Extra machine-readable details for the error response. */
    public Map<String, Object> details() {
        return Map.of();
    }
}
