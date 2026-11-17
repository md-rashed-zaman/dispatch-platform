package dev.rashed.dispatch.matching;

import dev.rashed.dispatch.tenancy.TenantContext;
import dev.rashed.dispatch.workorder.WorkOrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Matching", description = "Why these technicians")
@RestController
class MatchController {

    static final int EXPLAINED = 10;

    /** The ranking with every part of each score, and the weights that combine them. */
    record Explanation(
            MatchingProperties.Strategy strategy,
            MatchingProperties.Weights weights,
            boolean semantic,
            List<HybridRanker.Match> matches) {}

    private final WorkOrderService workOrders;
    private final HybridRanker ranker;
    private final MatchingProperties matching;

    MatchController(WorkOrderService workOrders, HybridRanker ranker, MatchingProperties matching) {
        this.workOrders = workOrders;
        this.ranker = ranker;
        this.matching = matching;
    }

    /**
     * The technicians hybrid ranking would pick for this work order now, best first, with each component.
     * Reading the work order checks the caller may see it (company staff, own company only).
     */
    @Operation(summary = "Explain the technician ranking for a work order (company staff)")
    @GetMapping("/api/work-orders/{id}/matches/explain")
    Explanation explain(@PathVariable UUID id) {
        var workOrder = workOrders.find(id);
        var tenant = TenantContext.current().orElseThrow();
        var job = ranker.jobVector(id, tenant, workOrder.title(), workOrder.description());
        var matches = ranker.rank(
                workOrder.site(), PublishedWorkOrderRouter.RADIUS_KM, workOrder.requiredSkills(), job, EXPLAINED);
        return new Explanation(matching.strategy(), matching.weights(), job.isPresent(), matches);
    }
}
