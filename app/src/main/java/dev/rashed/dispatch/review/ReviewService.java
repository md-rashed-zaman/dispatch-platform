package dev.rashed.dispatch.review;

import dev.rashed.dispatch.identity.CurrentUser;
import dev.rashed.dispatch.shared.NotFoundException;
import dev.rashed.dispatch.workorder.WorkOrderService;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The review queue: a company's staff see what the AI found and decide. Approving or rejecting goes through the
 * normal work order rules (WorkOrderService) in the same transaction that records the decision on the audit, so
 * the auditor, reading the resulting event, finds the audit already decided and credits the reviewer.
 */
@Service
@PreAuthorize("hasAnyRole('CLIENT_ADMIN', 'DISPATCHER')")
public class ReviewService {

    static final int QUEUE_LIMIT = 50;

    private final CompletionAudits audits;
    private final WorkOrderService workOrders;

    ReviewService(CompletionAudits audits, WorkOrderService workOrders) {
        this.audits = audits;
        this.workOrders = workOrders;
    }

    List<CompletionAudits.Audit> queue() {
        return audits.queue(QUEUE_LIMIT);
    }

    /** The newest audit of a work order the caller may see; 404 for another company's (row-level security). */
    CompletionAudits.Audit forWorkOrder(UUID workOrderId) {
        workOrders.find(workOrderId); // 404 if the caller can't see the work order
        return audits.latestFor(workOrderId)
                .orElseThrow(() -> new NotFoundException("Audit for work order", workOrderId));
    }

    @Transactional
    CompletionAudits.Audit approve(UUID auditId) {
        var audit = open(auditId);
        workOrders.approve(audit.workOrderId());
        audits.decide(auditId, CompletionAudits.State.APPROVED, reviewer(), null);
        return audits.find(auditId).orElseThrow();
    }

    @Transactional
    CompletionAudits.Audit reject(UUID auditId, String reason) {
        var audit = open(auditId);
        workOrders.reject(audit.workOrderId(), reason);
        audits.decide(auditId, CompletionAudits.State.REJECTED, reviewer(), reason);
        return audits.find(auditId).orElseThrow();
    }

    private CompletionAudits.Audit open(UUID auditId) {
        var audit = audits.find(auditId).orElseThrow(() -> new NotFoundException("Audit", auditId));
        if (audit.state() != CompletionAudits.State.NEEDS_REVIEW) {
            throw new AlreadyDecidedException(audit);
        }
        return audit;
    }

    private static String reviewer() {
        return CurrentUser.current().map(u -> u.id().toString()).orElseThrow();
    }
}
