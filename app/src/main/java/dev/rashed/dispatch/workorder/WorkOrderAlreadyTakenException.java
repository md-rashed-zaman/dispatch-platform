package dev.rashed.dispatch.workorder;

import dev.rashed.dispatch.shared.BusinessRuleException;
import java.util.UUID;

/** Another technician accepted first. */
class WorkOrderAlreadyTakenException extends BusinessRuleException {

    WorkOrderAlreadyTakenException(UUID workOrderId) {
        super("Another technician accepted work order " + workOrderId + " first.");
    }

    @Override
    public String type() {
        return "already-taken";
    }
}
