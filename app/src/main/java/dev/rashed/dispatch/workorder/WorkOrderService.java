package dev.rashed.dispatch.workorder;

import dev.rashed.dispatch.shared.NotFoundException;
import dev.rashed.dispatch.shared.StaleVersionException;
import dev.rashed.dispatch.tenancy.TenantContext;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.data.domain.Limit;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Work order use cases for company users. One transaction per method. No tenant checks here on purpose:
 * Hibernate and Postgres only ever return the caller's own tenant's rows, so another tenant's work order is
 * simply not found (ADR 0003).
 */
@Service
@PreAuthorize(WorkOrderService.COMPANY_STAFF)
public class WorkOrderService {

    static final String COMPANY_STAFF = "hasAnyRole('CLIENT_ADMIN', 'DISPATCHER')";

    private final WorkOrderRepository workOrders;

    WorkOrderService(WorkOrderRepository workOrders) {
        this.workOrders = workOrders;
    }

    @Transactional
    public WorkOrderView create(CreateWorkOrderRequest request) {
        var tenantId = TenantContext.current()
                .orElseThrow(() -> new AccessDeniedException("Only users of a company can create work orders"));
        var workOrder = WorkOrder.draft(
                tenantId,
                request.title(),
                request.description(),
                request.siteAddress(),
                request.site().toGeoPoint(),
                request.requiredSkills(),
                request.timeWindow().toTimeWindow(),
                request.budget().toMoney());
        request.checklist().forEach(workOrder::addChecklistItem);
        return WorkOrderView.of(workOrders.saveAndFlush(workOrder));
    }

    @Transactional(readOnly = true)
    public List<WorkOrderSummary> list(WorkOrderStatus status, int limit) {
        var found = status == null
                ? workOrders.findAllByOrderByCreatedAtDesc(Limit.of(limit))
                : workOrders.findByStatusOrderByCreatedAtDesc(status, Limit.of(limit));
        return found.stream().map(WorkOrderSummary::of).toList();
    }

    @Transactional(readOnly = true)
    public WorkOrderView find(UUID id) {
        return WorkOrderView.of(load(id));
    }

    /**
     * Applies an edit only if the caller saw the latest version (the ETag they send as If-Match). Without
     * this, two dispatchers editing the same draft would silently overwrite each other.
     */
    @Transactional
    public WorkOrderView revise(UUID id, long expectedVersion, WorkOrderChanges changes) {
        return change(id, workOrder -> {
            if (workOrder.version() != expectedVersion) {
                throw new StaleVersionException(expectedVersion, workOrder.version());
            }
            workOrder.revise(changes);
        });
    }

    @Transactional
    public WorkOrderView publish(UUID id) {
        return change(id, WorkOrder::publish);
    }

    @Transactional
    public WorkOrderView cancel(UUID id) {
        return change(id, WorkOrder::cancel);
    }

    @Transactional
    public WorkOrderView approve(UUID id) {
        return change(id, WorkOrder::approve);
    }

    @Transactional
    public WorkOrderView reject(UUID id, String reason) {
        return change(id, workOrder -> workOrder.reject(reason));
    }

    // save, not just dirty checking: Spring Data publishes the work order's events on save. The flush
    // writes now, so the returned view carries the new version, and a concurrent change made since we
    // loaded fails here (@Version) instead of being overwritten.
    private WorkOrderView change(UUID id, Consumer<WorkOrder> action) {
        var workOrder = load(id);
        action.accept(workOrder);
        return WorkOrderView.of(workOrders.saveAndFlush(workOrder));
    }

    private WorkOrder load(UUID id) {
        return workOrders.findById(id).orElseThrow(() -> new NotFoundException("Work order", id));
    }
}
