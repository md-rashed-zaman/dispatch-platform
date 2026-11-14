package dev.rashed.dispatch.workorder;

import dev.rashed.dispatch.shared.Cursor;
import dev.rashed.dispatch.shared.CursorPage;
import dev.rashed.dispatch.shared.InvalidCursorException;
import dev.rashed.dispatch.shared.NotFoundException;
import dev.rashed.dispatch.shared.Skill;
import dev.rashed.dispatch.shared.StaleVersionException;
import dev.rashed.dispatch.shared.Timestamps;
import dev.rashed.dispatch.technician.TechnicianSearch;
import dev.rashed.dispatch.tenancy.TenantContext;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
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
    static final int ROUTING_RADIUS_KM = 20;
    static final int OFFERS_PER_WORK_ORDER = 10;
    static final Duration OFFER_LIFETIME = Duration.ofMinutes(30);

    private final WorkOrderRepository workOrders;
    private final OfferRepository offers;
    private final TechnicianSearch technicians;

    WorkOrderService(WorkOrderRepository workOrders, OfferRepository offers, TechnicianSearch technicians) {
        this.workOrders = workOrders;
        this.offers = offers;
        this.technicians = technicians;
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

    /**
     * @param after the {@code next} cursor of the previous page, or null for the first page
     * @throws InvalidCursorException if {@code after} isn't a cursor from this list
     */
    @Transactional(readOnly = true)
    public CursorPage<WorkOrderSummary> list(@Nullable WorkOrderStatus status, int limit, @Nullable String after) {
        var rows = Limit.of(limit + 1); // one extra row shows whether there's a next page
        List<WorkOrder> found;
        if (after == null) {
            found = status == null
                    ? workOrders.findAllByOrderByCreatedAtDescIdDesc(rows)
                    : workOrders.findByStatusOrderByCreatedAtDescIdDesc(status, rows);
        } else {
            var cursor = Cursor.decode(after);
            var createdAt = createdAtOf(cursor);
            found = status == null
                    ? workOrders.findPageAfter(createdAt, cursor.id(), rows)
                    : workOrders.findPageByStatusAfter(status, createdAt, cursor.id(), rows);
        }
        return CursorPage.of(found, limit, w -> new Cursor(w.createdAt().toString(), w.id()))
                .map(WorkOrderSummary::of);
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

    /**
     * Publishes the draft and offers it to the nearest technicians who have every required skill. With
     * nobody in range it stays PUBLISHED.
     */
    @Transactional
    public WorkOrderView publish(UUID id) {
        return change(id, workOrder -> {
            workOrder.publish();
            routeToNearbyTechnicians(workOrder);
        });
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

    // shortcut: routing runs inside publish, so a slow search slows publishing; Phase 2 moves it to an
    // event listener. Widening the radius when nobody is in range comes with the SLA policy (step 2.5).
    private void routeToNearbyTechnicians(WorkOrder workOrder) {
        var skills = workOrder.requiredSkills().stream().map(Skill::code).toList();
        var candidates = technicians
                .nearby(workOrder.site(), ROUTING_RADIUS_KM, skills, OFFERS_PER_WORK_ORDER, null)
                .items();
        if (candidates.isEmpty()) {
            return;
        }
        workOrder.route();
        var expiresAt = Timestamps.now().plus(OFFER_LIFETIME);
        candidates.forEach(t -> offers.save(Offer.create(workOrder.id(), t.id(), expiresAt)));
    }

    // save, not just dirty checking: Spring Data publishes the work order's events on save. The flush
    // writes now, so the returned view carries the new version, and a concurrent change made since we
    // loaded fails here (@Version) instead of being overwritten.
    private WorkOrderView change(UUID id, Consumer<WorkOrder> action) {
        var workOrder = load(id);
        action.accept(workOrder);
        return WorkOrderView.of(workOrders.saveAndFlush(workOrder));
    }

    private static Instant createdAtOf(Cursor cursor) {
        try {
            return Instant.parse(cursor.sortValue());
        } catch (DateTimeParseException e) {
            throw new InvalidCursorException();
        }
    }

    private WorkOrder load(UUID id) {
        return workOrders.findById(id).orElseThrow(() -> new NotFoundException("Work order", id));
    }
}
