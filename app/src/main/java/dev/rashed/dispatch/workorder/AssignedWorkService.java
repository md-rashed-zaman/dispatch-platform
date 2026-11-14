package dev.rashed.dispatch.workorder;

import dev.rashed.dispatch.shared.NotFoundException;
import dev.rashed.dispatch.tenancy.TenantContext;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The assigned technician's side of the lifecycle: start the job, submit it for approval. Like accepting an
 * offer (ADR 0005), the caller is checked as the technician, then the change runs as the work order's
 * tenant through the normal domain methods.
 */
@Service
@PreAuthorize("hasRole('TECHNICIAN')")
public class AssignedWorkService {

    private final WorkOrderAccess access;
    private final WorkOrderRepository workOrders;
    private final TransactionTemplate transaction;

    AssignedWorkService(
            WorkOrderAccess access, WorkOrderRepository workOrders, PlatformTransactionManager transactionManager) {
        this.access = access;
        this.workOrders = workOrders;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public WorkOrderView start(UUID workOrderId) {
        return change(workOrderId, WorkOrder::start);
    }

    public WorkOrderView submit(UUID workOrderId) {
        return change(workOrderId, WorkOrder::submit);
    }

    private WorkOrderView change(UUID workOrderId, Consumer<WorkOrder> action) {
        // Only the assigned technician finds it; anyone else gets 404, like another tenant's work order.
        var ref = access.forCaller(workOrderId).orElseThrow(() -> new NotFoundException("Work order", workOrderId));
        return TenantContext.callAs(
                ref.tenantId(),
                () -> Objects.requireNonNull(transaction.execute(status -> {
                    var workOrder = workOrders.findById(workOrderId).orElseThrow();
                    action.accept(workOrder);
                    return WorkOrderView.of(workOrders.saveAndFlush(workOrder));
                })));
    }
}
