package dev.rashed.dispatch.matching;

import dev.rashed.dispatch.technician.NearbyTechnician;
import dev.rashed.dispatch.technician.TechnicianSearch;
import dev.rashed.dispatch.tenancy.TenantContext;
import dev.rashed.dispatch.workorder.WorkOrderPublished;
import dev.rashed.dispatch.workorder.WorkOrderRouting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Offers each published work order to the nearest technicians with every required skill. With nobody in
 * range the work order stays PUBLISHED (widening the radius comes with the SLA policy, step 2.5).
 */
@Component
class PublishedWorkOrderRouter {

    private static final Logger log = LoggerFactory.getLogger(PublishedWorkOrderRouter.class);

    static final int RADIUS_KM = 20;
    static final int OFFERS_PER_WORK_ORDER = 10;

    private final TechnicianSearch technicians;
    private final WorkOrderRouting routing;

    PublishedWorkOrderRouter(TechnicianSearch technicians, WorkOrderRouting routing) {
        this.technicians = technicians;
        this.routing = routing;
    }

    // After commit: the publish really happened. On another thread: publishing doesn't wait for the search.
    // Spring Modulith records the event before the commit and marks it done when this method returns, so a
    // crash or an exception here leaves it incomplete, to be delivered again (T16). Not
    // @ApplicationModuleListener: its transaction would start before we name the tenant, and row-level
    // security would hide the work order.
    @Async
    @TransactionalEventListener
    void on(WorkOrderPublished event) {
        var candidates =
                technicians.nearest(event.site(), RADIUS_KM, event.requiredSkills(), OFFERS_PER_WORK_ORDER).stream()
                        .map(NearbyTechnician::id)
                        .toList();
        var routed = TenantContext.callAs(event.tenantId(), () -> routing.offer(event.workOrderId(), candidates));
        log.info(
                "Work order {}: {} candidates, {}",
                event.workOrderId(),
                candidates.size(),
                routed ? "offers sent" : "nothing to do");
    }
}
