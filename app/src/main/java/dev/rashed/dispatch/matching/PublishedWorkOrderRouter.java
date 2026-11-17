package dev.rashed.dispatch.matching;

import dev.rashed.dispatch.technician.NearbyTechnician;
import dev.rashed.dispatch.technician.TechnicianSearch;
import dev.rashed.dispatch.tenancy.TenantContext;
import dev.rashed.dispatch.workorder.WorkOrderPublished;
import dev.rashed.dispatch.workorder.WorkOrderRouting;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Offers each published work order to the best technicians nearby: by hybrid score (meaning, skills, distance,
 * rating, reliability; step 3.4), or with strategy {@code tags} the nearest with every required skill. With
 * nobody in range the work order stays PUBLISHED; after 2 hours {@link SlaPolicy} offers it farther away.
 */
@Component
class PublishedWorkOrderRouter {

    private static final Logger log = LoggerFactory.getLogger(PublishedWorkOrderRouter.class);

    static final int RADIUS_KM = 20;
    static final int OFFERS_PER_WORK_ORDER = 10;

    private final TechnicianSearch technicians;
    private final WorkOrderRouting routing;
    private final HybridRanker ranker;
    private final MatchingProperties matching;

    PublishedWorkOrderRouter(
            TechnicianSearch technicians, WorkOrderRouting routing, HybridRanker ranker, MatchingProperties matching) {
        this.technicians = technicians;
        this.routing = routing;
        this.ranker = ranker;
        this.matching = matching;
    }

    // After commit: the publish really happened. On another thread: publishing doesn't wait for the search.
    // Spring Modulith records the event before the commit and marks it done when this method returns, so a
    // crash or an exception here leaves it incomplete, to be delivered again (T16). Not
    // @ApplicationModuleListener: its transaction would start before we name the tenant, and row-level
    // security would hide the work order.
    @Async
    @TransactionalEventListener
    void on(WorkOrderPublished event) {
        var candidates = TenantContext.callAs(event.tenantId(), () -> candidates(event));
        var routed = TenantContext.callAs(event.tenantId(), () -> routing.offer(event.workOrderId(), candidates));
        log.info(
                "Work order {}: {} candidates, {}",
                event.workOrderId(),
                candidates.size(),
                routed ? "offers sent" : "nothing to do");
    }

    private List<UUID> candidates(WorkOrderPublished event) {
        if (matching.strategy() == MatchingProperties.Strategy.TAGS) {
            return technicians.nearest(event.site(), RADIUS_KM, event.requiredSkills(), OFFERS_PER_WORK_ORDER).stream()
                    .map(NearbyTechnician::id)
                    .toList();
        }
        // The embedding call takes a second or two; this runs after the commit, on its own thread.
        var job = ranker.jobVector(event.workOrderId(), event.tenantId(), event.title(), event.description());
        return ranker.rank(event.site(), RADIUS_KM, event.requiredSkills(), job, OFFERS_PER_WORK_ORDER).stream()
                .map(HybridRanker.Match::technicianId)
                .toList();
    }
}
