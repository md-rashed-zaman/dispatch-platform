package dev.rashed.dispatch.workorder;

import dev.rashed.dispatch.shared.Money;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the payment module (step 4.2) needs from a work order. Call as the work order's tenant: row-level security
 * applies as for everyone, so another company's work order is simply not found.
 */
@Service
public class WorkOrderFunding {

    public record Snapshot(
            UUID id, UUID tenantId, String title, WorkOrderStatus status, Money budget, boolean funded) {}

    private final WorkOrderRepository workOrders;

    WorkOrderFunding(WorkOrderRepository workOrders) {
        this.workOrders = workOrders;
    }

    @Transactional(readOnly = true)
    public Optional<Snapshot> snapshot(UUID workOrderId) {
        return workOrders
                .findById(workOrderId)
                .map(w -> new Snapshot(w.id(), w.tenantId(), w.title(), w.status(), w.budget(), w.fundedAt() != null));
    }

    /**
     * Records the capture on the work order, in the caller's transaction: a payment is captured exactly when its
     * work order is funded.
     *
     * @return false if the work order can't take money any more (cancelled while the client was paying): the
     *     payment is still captured, since the money arrived, and must be refunded
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean markFunded(UUID workOrderId, Instant at) {
        var workOrder = workOrders.findById(workOrderId).orElseThrow();
        if (workOrder.status() != WorkOrderStatus.ASSIGNED && workOrder.status() != WorkOrderStatus.IN_PROGRESS) {
            return false;
        }
        workOrder.markFunded(at);
        workOrders.saveAndFlush(workOrder);
        return true;
    }

    /** The technician was paid: APPROVED to PAID, in the caller's transaction. False if it isn't APPROVED. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean markPaid(UUID workOrderId) {
        var workOrder = workOrders.findById(workOrderId).orElseThrow();
        if (workOrder.status() != WorkOrderStatus.APPROVED) {
            return false;
        }
        workOrder.markPaid();
        workOrders.saveAndFlush(workOrder); // publishes the status change, like every transition
        return true;
    }
}
