package dev.rashed.dispatch.workorder;

import dev.rashed.dispatch.shared.Timestamps;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns matching's choice of technicians into offers. Offers stay in this module because accepting one and
 * assigning the work order change together in one transaction (ADR 0005). For system callers in the work
 * order's tenant context, not for HTTP requests.
 */
@Service
public class WorkOrderRouting {

    static final Duration OFFER_LIFETIME = Duration.ofMinutes(30);

    private final WorkOrderRepository workOrders;
    private final OfferRepository offers;

    WorkOrderRouting(WorkOrderRepository workOrders, OfferRepository offers) {
        this.workOrders = workOrders;
        this.offers = offers;
    }

    /**
     * Offers the work order to these technicians and marks it ROUTED, if it's still PUBLISHED. Doing nothing
     * otherwise makes it safe to deliver the same event twice, or after the work order was cancelled.
     *
     * @return whether offers were made
     */
    @Transactional
    public boolean offer(UUID workOrderId, List<UUID> technicianIds) {
        var workOrder = workOrders.findById(workOrderId).orElse(null);
        if (workOrder == null || workOrder.status() != WorkOrderStatus.PUBLISHED || technicianIds.isEmpty()) {
            return false;
        }
        workOrder.route();
        var expiresAt = Timestamps.now().plus(OFFER_LIFETIME);
        technicianIds.forEach(technicianId -> offers.save(Offer.create(workOrderId, technicianId, expiresAt)));
        workOrders.save(workOrder); // @Version: a concurrent change (a cancel) fails this instead of being lost
        return true;
    }
}
