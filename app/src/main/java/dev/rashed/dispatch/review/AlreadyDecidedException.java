package dev.rashed.dispatch.review;

import dev.rashed.dispatch.shared.BusinessRuleException;
import java.util.Map;

/** The audit was decided already (by the policy, another reviewer, or on the work order itself): 409. */
class AlreadyDecidedException extends BusinessRuleException {

    private final CompletionAudits.Audit audit;

    AlreadyDecidedException(CompletionAudits.Audit audit) {
        super("This audit was already decided: " + audit.state());
        this.audit = audit;
    }

    @Override
    public String type() {
        return "already-decided";
    }

    @Override
    public Map<String, Object> details() {
        return Map.of("state", audit.state().name());
    }
}
