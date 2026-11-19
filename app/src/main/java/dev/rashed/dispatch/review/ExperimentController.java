package dev.rashed.dispatch.review;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Experiments", description = "The AI auto-approval rollout, arm by arm (platform admins)")
@RestController
class ExperimentController {

    /**
     * Per arm ({@code on}: auto-approval for the company; {@code off}: a person decides everything). The primary
     * metric is the time from the AI check to approval; the guardrail is how often a person rejects work the AI
     * would have approved ({@code aiApprovableRejected} of {@code aiApprovableDecidedByPerson}, measured in the off
     * arm, where people see those jobs).
     *
     * @param enoughData both arms have at least {@link #MIN_DECISIONS_PER_ARM} decisions: before that, differences
     *     are mostly noise, and looking early and stopping on a good day is how experiments lie (T35)
     */
    record Arm(
            String variant,
            long audits,
            long decided,
            long approved,
            long rejected,
            long autoApproved,
            @Nullable BigDecimal minutesToApprovalP50,
            @Nullable BigDecimal minutesToApprovalP90,
            long aiApprovableDecidedByPerson,
            long aiApprovableRejectedByPerson) {}

    record Report(Instant since, List<Arm> arms, boolean enoughData, int minDecisionsPerArm) {}

    /** Before reading the result. Sized in T35 for the guardrail: a 2% rejection rate needs about this many to see. */
    static final int MIN_DECISIONS_PER_ARM = 200;

    private final JdbcClient jdbc;

    ExperimentController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Operation(summary = "The ai.auto-approve experiment: each arm's decisions, time to approval, and the guardrail")
    @GetMapping("/api/experiments/auto-approve")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @Transactional(readOnly = true)
    Report autoApprove(@RequestParam(defaultValue = "30") @Min(1) @Max(365) int days) {
        var since = Instant.now().minus(Duration.ofDays(days));
        var arms = jdbc.sql("SELECT * FROM auto_approve_experiment(:since) ORDER BY variant")
                .param("since", Timestamp.from(since))
                .query((rs, row) -> new Arm(
                        rs.getString("variant"),
                        rs.getLong("audits"),
                        rs.getLong("decided"),
                        rs.getLong("approved"),
                        rs.getLong("rejected"),
                        rs.getLong("auto_approved"),
                        rs.getBigDecimal("minutes_to_approval_p50"),
                        rs.getBigDecimal("minutes_to_approval_p90"),
                        rs.getLong("ai_approvable_decided_by_person"),
                        rs.getLong("ai_approvable_rejected_by_person")))
                .list();
        var enough = arms.size() == 2 && arms.stream().allMatch(a -> a.decided() >= MIN_DECISIONS_PER_ARM);
        return new Report(since, arms, enough, MIN_DECISIONS_PER_ARM);
    }
}
