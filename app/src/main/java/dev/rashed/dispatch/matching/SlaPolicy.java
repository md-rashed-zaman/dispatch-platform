package dev.rashed.dispatch.matching;

import dev.rashed.dispatch.shared.Timestamps;
import dev.rashed.dispatch.technician.NearbyTechnician;
import dev.rashed.dispatch.technician.TechnicianSearch;
import dev.rashed.dispatch.tenancy.TenantContext;
import dev.rashed.dispatch.tenancy.TenantDirectory;
import dev.rashed.dispatch.workorder.SlaStage;
import dev.rashed.dispatch.workorder.WorkOrderSla;
import java.time.Duration;
import java.util.EnumSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Work orders nobody takes (T22): after 2 hours, offer them to technicians up to 40 km away; after 6 hours,
 * tell the company. Runs every minute on one instance at a time (ShedLock), and every step is also safe if
 * two instances ever run it together: {@link WorkOrderSla} changes each work order once.
 */
@Component
class SlaPolicy {

    private static final Logger log = LoggerFactory.getLogger(SlaPolicy.class);

    static final int WIDER_OFFERS = 20;

    private final TenantDirectory tenants;
    private final WorkOrderSla sla;
    private final TechnicianSearch technicians;
    private final Duration widenAfter;
    private final Duration escalateAfter;
    private final int widenedRadiusKm;
    private final String instance;

    SlaPolicy(
            TenantDirectory tenants,
            WorkOrderSla sla,
            TechnicianSearch technicians,
            @Value("${dispatch.sla.widen-after:PT2H}") Duration widenAfter,
            @Value("${dispatch.sla.escalate-after:PT6H}") Duration escalateAfter,
            @Value("${dispatch.sla.widened-radius-km:40}") int widenedRadiusKm,
            @Value("${dispatch.instance-name}") String instance) {
        this.tenants = tenants;
        this.sla = sla;
        this.technicians = technicians;
        this.widenAfter = widenAfter;
        this.escalateAfter = escalateAfter;
        this.widenedRadiusKm = widenedRadiusKm;
        this.instance = instance;
    }

    // lockAtLeastFor: the lock is held a little longer than the round takes, so an instance whose clock or
    // schedule is slightly off doesn't start the same round again right after. lockAtMostFor: if this instance
    // dies holding the lock, another takes over after that long.
    @Scheduled(
            fixedDelayString = "${dispatch.sla.check-interval:PT1M}",
            initialDelayString = "${dispatch.sla.initial-delay:PT30S}")
    @SchedulerLock(
            name = "sla-policy",
            lockAtMostFor = "${dispatch.sla.lock-at-most-for:PT5M}",
            lockAtLeastFor = "${dispatch.sla.lock-at-least-for:PT20S}")
    public void run() {
        var started = System.nanoTime();
        var acted = new AtomicInteger();
        var now = Timestamps.now();
        for (var tenantId : tenants.allTenantIds()) {
            TenantContext.runAs(tenantId, () -> {
                // Escalate first: a work order that waited 6 hours doesn't need widening any more.
                for (var waiting :
                        sla.waitingSince(now.minus(escalateAfter), EnumSet.of(SlaStage.ON_TRACK, SlaStage.WIDENED))) {
                    once(acted, waiting.id(), "escalate", () -> sla.escalate(waiting.id()) ? "company told" : null);
                }
                for (var waiting : sla.waitingSince(now.minus(widenAfter), EnumSet.of(SlaStage.ON_TRACK))) {
                    once(acted, waiting.id(), "widen", () -> {
                        var candidates = technicians
                                .nearest(waiting.site(), widenedRadiusKm, waiting.requiredSkills(), WIDER_OFFERS)
                                .stream()
                                .map(NearbyTechnician::id)
                                .toList();
                        var offered = sla.widen(waiting.id(), candidates);
                        return offered < 0 ? null : offered + " new offers within " + widenedRadiusKm + " km";
                    });
                }
            });
        }
        // INFO only when something happened: a quiet round every minute would flood the log.
        var tookMs = (System.nanoTime() - started) / 1_000_000;
        if (acted.get() > 0) {
            log.info("SLA round on {}: {} work orders handled in {} ms", instance, acted.get(), tookMs);
        } else {
            log.debug("SLA round on {}: nothing to do ({} ms)", instance, tookMs);
        }
    }

    private interface Step {
        /** @return what was done, or null if there was nothing to do */
        String apply();
    }

    private void once(AtomicInteger acted, UUID workOrderId, String step, Step action) {
        try {
            var done = action.apply();
            if (done != null) {
                acted.incrementAndGet();
                log.info("SLA {} work order {}: {} (on {})", step, workOrderId, done, instance);
            }
        } catch (OptimisticLockingFailureException concurrent) {
            // Another instance changed it between our read and our save; its change stands, ours rolled back.
            log.info("SLA {} work order {}: done elsewhere at the same moment (on {})", step, workOrderId, instance);
        }
    }
}
