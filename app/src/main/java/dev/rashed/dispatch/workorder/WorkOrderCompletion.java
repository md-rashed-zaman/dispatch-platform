package dev.rashed.dispatch.workorder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What the completion audit (step 3.5) needs from a work order, for a system caller with no signed-in user. Call
 * as the work order's tenant ({@code TenantContext}): row-level security applies as for everyone.
 */
@Service
public class WorkOrderCompletion {

    public record Snapshot(
            UUID id,
            String title,
            String description,
            WorkOrderStatus status,
            @Nullable String notes,
            List<WorkOrderView.ChecklistItemView> checklist) {}

    private final WorkOrderRepository workOrders;
    private final TransactionTemplate transaction;

    WorkOrderCompletion(WorkOrderRepository workOrders, PlatformTransactionManager transactions) {
        this.workOrders = workOrders;
        this.transaction = new TransactionTemplate(transactions);
    }

    public Optional<Snapshot> snapshot(UUID workOrderId) {
        return transaction.execute(status -> workOrders
                .findById(workOrderId)
                .map(w -> new Snapshot(
                        w.id(),
                        w.title(),
                        w.description(),
                        w.status(),
                        w.completionNotes(),
                        w.checklist().stream()
                                .map(item -> new WorkOrderView.ChecklistItemView(item.position(), item.description()))
                                .toList())));
    }

    /**
     * Approves as the system, only if the work order is still waiting for approval. False when a person decided
     * first (or at the same moment: the version check lets only one of them win), which is not an error.
     */
    public boolean approveAsSystem(UUID workOrderId) {
        try {
            return Boolean.TRUE.equals(transaction.execute(status -> {
                var workOrder = workOrders.findById(workOrderId).orElse(null);
                if (workOrder == null || workOrder.status() != WorkOrderStatus.SUBMITTED) {
                    return false;
                }
                workOrder.approve();
                workOrders.saveAndFlush(workOrder); // publishes the status change, like any approval
                return true;
            }));
        } catch (ObjectOptimisticLockingFailureException concurrentDecision) {
            return false;
        }
    }
}
