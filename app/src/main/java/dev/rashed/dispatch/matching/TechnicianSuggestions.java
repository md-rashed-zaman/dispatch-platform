package dev.rashed.dispatch.matching;

import dev.rashed.dispatch.tenancy.TenantContext;
import dev.rashed.dispatch.workorder.WorkOrderService;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The technicians hybrid ranking would pick for a work order now, best first, with every part of the score
 * (step 3.4). Reading the work order checks the caller may see it: company staff, own company only.
 */
@Service
public class TechnicianSuggestions {

    /** One candidate, in plain numbers a person (or an assistant) can read. */
    public record Suggestion(
            UUID technicianId,
            String fullName,
            double distanceKm,
            List<String> skills,
            double score,
            Double meaning,
            double skillCoverage,
            double rating,
            double reliability) {}

    public record Suggestions(boolean semantic, List<Suggestion> technicians) {}

    private final WorkOrderService workOrders;
    private final HybridRanker ranker;

    TechnicianSuggestions(WorkOrderService workOrders, HybridRanker ranker) {
        this.workOrders = workOrders;
        this.ranker = ranker;
    }

    public Suggestions forWorkOrder(UUID workOrderId, int limit) {
        var workOrder = workOrders.find(workOrderId);
        var tenant = TenantContext.current().orElseThrow();
        var job = ranker.jobVector(workOrderId, tenant, workOrder.title(), workOrder.description());
        var matches = ranker.rank(
                workOrder.site(), PublishedWorkOrderRouter.RADIUS_KM, workOrder.requiredSkills(), job, limit);
        return new Suggestions(
                job.isPresent(),
                matches.stream()
                        .map(m -> new Suggestion(
                                m.technicianId(),
                                m.fullName(),
                                m.distanceKm(),
                                m.skills(),
                                m.total(),
                                m.semantic(),
                                m.skillCoverage(),
                                m.rating(),
                                m.reliability()))
                        .toList());
    }
}
