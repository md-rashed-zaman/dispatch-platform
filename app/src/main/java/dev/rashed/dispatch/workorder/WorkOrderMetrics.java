package dev.rashed.dispatch.workorder;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Business metrics (T25). No tenant or work order tags: every distinct tag value is a separate time series,
 * and IDs would create millions (cardinality). Per-tenant questions go to traces and logs.
 */
@Component
class WorkOrderMetrics {

    private final Counter published;
    private final Counter acceptConflicts;
    private final Timer timeToAssign;

    WorkOrderMetrics(MeterRegistry registry) {
        this.published = Counter.builder("dispatch.work_orders.published")
                .description("Work orders published")
                .register(registry);
        this.acceptConflicts = Counter.builder("dispatch.offers.accept_conflicts")
                .description("Accepts that lost the race: someone else already had the job")
                .register(registry);
        // Buckets at the SLO thresholds, so "share assigned within 30 minutes" is one bucket over the count.
        this.timeToAssign = Timer.builder("dispatch.work_orders.time_to_assign")
                .description("From publish to a technician accepting")
                .serviceLevelObjectives(
                        Duration.ofMinutes(5),
                        Duration.ofMinutes(15),
                        Duration.ofMinutes(30),
                        Duration.ofHours(1),
                        Duration.ofHours(2),
                        Duration.ofHours(6))
                .register(registry);
    }

    @TransactionalEventListener // committed publishes only
    void on(WorkOrderPublished event) {
        published.increment();
    }

    void acceptConflict() {
        acceptConflicts.increment();
    }

    /** Recorded once the accept commits: an accept that rolls back (the offer expired) assigned nothing. */
    void assigned(Instant publishedAt, Instant assignedAt) {
        var waited = Duration.between(publishedAt, assignedAt);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                timeToAssign.record(waited);
            }
        });
    }
}
