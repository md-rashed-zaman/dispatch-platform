package dev.rashed.dispatch.workorder;

import java.util.Optional;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class WorkOrderService {

    private final WorkOrderRepository workOrders;

    WorkOrderService(WorkOrderRepository workOrders) {
        this.workOrders = workOrders;
    }

    /**
     * No tenant check here on purpose: Hibernate and Postgres only ever return the caller's own tenant's
     * rows, so another tenant's work order is simply not found.
     */
    @PreAuthorize("hasAnyRole('CLIENT_ADMIN', 'DISPATCHER')")
    @Transactional(readOnly = true)
    public Optional<WorkOrderView> find(UUID id) {
        return workOrders.findById(id).map(WorkOrderView::of);
    }
}
