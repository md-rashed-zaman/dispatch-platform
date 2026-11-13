package dev.rashed.dispatch.workorder;

import dev.rashed.dispatch.shared.BusinessRuleException;
import java.util.Map;

/** A lifecycle move that the transition table doesn't allow, like approving a cancelled work order. */
public class InvalidTransitionException extends BusinessRuleException {

    private final WorkOrderStatus current;
    private final String action;

    InvalidTransitionException(String action, WorkOrderStatus current) {
        super("Can't " + action + " a work order that is " + current);
        this.current = current;
        this.action = action;
    }

    @Override
    public String type() {
        return "invalid-transition";
    }

    @Override
    public Map<String, Object> details() {
        return Map.of("currentStatus", current, "action", action);
    }

    public WorkOrderStatus current() {
        return current;
    }

    public String action() {
        return action;
    }
}
