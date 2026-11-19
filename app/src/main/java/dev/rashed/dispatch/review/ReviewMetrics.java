package dev.rashed.dispatch.review;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The auto-approval experiment's numbers (step 4.4, T35), per arm ({@code variant}: on, off, or none for audits
 * before the experiment):
 *
 * <ul>
 *   <li>{@code dispatch.review.time_to_decision}: from the AI check (just after submission) to the decision, by
 *       outcome and by who decided. The primary metric: auto-approval should make "approved" much faster.
 *   <li>{@code dispatch.review.ai_approvable}: decisions on jobs the AI would have approved on its own. In the off
 *       arm a person decides them; the share they reject is the guardrail (what auto-approval would get wrong).
 * </ul>
 */
@Component
class ReviewMetrics {

    private final MeterRegistry registry;

    ReviewMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    void decided(
            @Nullable String variant, String outcome, String by, Instant auditedAt, @Nullable Boolean aiWouldApprove) {
        var arm = variant == null ? "none" : variant;
        Timer.builder("dispatch.review.time_to_decision")
                .description("From the AI check to the decision")
                .tags("variant", arm, "outcome", outcome, "by", by)
                .publishPercentileHistogram()
                .register(registry)
                .record(Duration.between(auditedAt, Instant.now()));
        if (Boolean.TRUE.equals(aiWouldApprove)) {
            registry.counter("dispatch.review.ai_approvable", "variant", arm, "outcome", outcome, "by", by)
                    .increment();
        }
    }

    /** The work order's new status when it was decided outside the queue, as an outcome. */
    static String outcome(String status) {
        return switch (status) {
            case "APPROVED", "AUTO_APPROVED" -> "approved";
            case "IN_PROGRESS", "REJECTED" -> "rejected"; // sent back to the technician
            default -> "other";
        };
    }
}
