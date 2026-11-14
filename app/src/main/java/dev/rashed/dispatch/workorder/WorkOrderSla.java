package dev.rashed.dispatch.workorder;

import dev.rashed.dispatch.shared.GeoPoint;
import dev.rashed.dispatch.shared.Skill;
import dev.rashed.dispatch.shared.Timestamps;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the SLA policy (matching module) may do to work orders waiting for a technician. Every step is
 * conditional on the work order's state and protected by its version, so a step runs once even if two
 * instances try it at the same moment: the second one's save fails, and its transaction rolls back.
 * All methods work on the current tenant.
 */
@Service
public class WorkOrderSla {

    private static final List<WorkOrderStatus> WAITING = List.of(WorkOrderStatus.PUBLISHED, WorkOrderStatus.ROUTED);
    private static final int BATCH = 100;

    public record Waiting(UUID id, GeoPoint site, List<String> requiredSkills, Instant waitingSince) {}

    private final WorkOrderRepository workOrders;
    private final OfferRepository offers;

    WorkOrderSla(WorkOrderRepository workOrders, OfferRepository offers) {
        this.workOrders = workOrders;
        this.offers = offers;
    }

    @Transactional(readOnly = true)
    public List<Waiting> waitingSince(Instant cutoff, Set<SlaStage> stages) {
        return workOrders.findWaitingSince(WAITING, cutoff, stages, Limit.of(BATCH)).stream()
                .map(w -> new Waiting(
                        w.id(),
                        w.site(),
                        w.requiredSkills().stream().map(Skill::code).sorted().toList(),
                        w.publishedAt()))
                .toList();
    }

    /**
     * Offers the work order to these technicians too (skipping anyone already offered it) and records that
     * the search was widened.
     *
     * @return how many new offers were made, or -1 if it isn't waiting anymore or was already widened
     */
    @Transactional
    public int widen(UUID workOrderId, List<UUID> technicianIds) {
        var workOrder = workOrders.findById(workOrderId).orElse(null);
        if (workOrder == null || !workOrder.widenSearch()) {
            return -1;
        }
        var alreadyOffered = new HashSet<>(offers.findTechnicianIdsByWorkOrderId(workOrderId));
        var expiresAt = Timestamps.now().plus(WorkOrderRouting.OFFER_LIFETIME);
        var fresh = technicianIds.stream()
                .filter(id -> !alreadyOffered.contains(id))
                .toList();
        fresh.forEach(technicianId -> offers.save(Offer.create(workOrderId, technicianId, expiresAt)));
        if (!fresh.isEmpty() && workOrder.status() == WorkOrderStatus.PUBLISHED) {
            workOrder.route(); // nobody was in range at first; now somebody is
        }
        workOrders.save(workOrder); // @Version: if another instance got here first, this fails and rolls back
        return fresh.size();
    }

    /** @return whether the company is being told now (false: not waiting anymore, or told already) */
    @Transactional
    public boolean escalate(UUID workOrderId) {
        var workOrder = workOrders.findById(workOrderId).orElse(null);
        if (workOrder == null || !workOrder.escalate()) {
            return false;
        }
        workOrders.save(workOrder); // publishes SlaBreached into the outbox, in this transaction
        return true;
    }
}
