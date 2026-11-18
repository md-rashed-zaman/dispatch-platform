package dev.rashed.dispatch.review;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rashed.dispatch.ai.CompletionCheck;
import dev.rashed.dispatch.ai.CompletionCheck.Item;
import dev.rashed.dispatch.ai.CompletionCheck.Verdict;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The policy approves only when everything points the same way; every doubt goes to a person. */
class AuditPolicyTest {

    static final double THRESHOLD = 0.9;

    @Test
    void everyItemMetAndSureEnoughIsApproved() {
        var decision = decide(0.95, Verdict.MET, Verdict.MET);
        assertThat(decision.recommendation()).isEqualTo(AuditPolicy.Recommendation.APPROVE);
        assertThat(decision.reason()).contains("All 2 checklist items are shown", "95% sure");
        assertThat(decide(0.95, Verdict.MET).reason()).startsWith("The checklist item is shown");
    }

    @Test
    void exactlyAtTheThresholdIsApproved() {
        assertThat(decide(0.9, Verdict.MET).recommendation()).isEqualTo(AuditPolicy.Recommendation.APPROVE);
    }

    @Test
    void notSureEnoughGoesToAPerson() {
        var decision = decide(0.89, Verdict.MET, Verdict.MET);
        assertThat(decision.recommendation()).isEqualTo(AuditPolicy.Recommendation.REVIEW);
        assertThat(decision.reason()).contains("only 89% sure", "needs 90%");
    }

    @Test
    void anyItemNotMetOrUnclearGoesToAPersonWhateverTheConfidence() {
        assertThat(decide(1.0, Verdict.MET, Verdict.NOT_MET).reason())
                .startsWith("Item 2 looks not done"); // as numbered on screen
        assertThat(decide(1.0, Verdict.UNCLEAR, Verdict.MET).reason()).startsWith("Item 1 is unclear");
        assertThat(decide(1.0, Verdict.UNCLEAR, Verdict.NOT_MET).reason())
                .startsWith("Item 2 looks not done"); // worst first
    }

    @Test
    void noPhotosOrNoAnswerGoesToAPerson() {
        assertThat(AuditPolicy.decide(Optional.empty(), 0, THRESHOLD, true).reason())
                .startsWith("No photos");
        assertThat(AuditPolicy.decide(Optional.empty(), 3, THRESHOLD, true).reason())
                .contains("couldn't run");
        // Photos but the model was skipped can't happen; a stored assessment without photos still never approves.
        var assessment = assessment(1.0, Verdict.MET);
        assertThat(AuditPolicy.decide(assessment, 0, THRESHOLD, true).recommendation())
                .isEqualTo(AuditPolicy.Recommendation.REVIEW);
    }

    @Test
    void theSwitchOffSendsEvenAPerfectAssessmentToAPerson() {
        var decision = AuditPolicy.decide(assessment(1.0, Verdict.MET), 2, THRESHOLD, false);
        assertThat(decision.recommendation()).isEqualTo(AuditPolicy.Recommendation.REVIEW);
        assertThat(decision.reason()).contains("switched off");
    }

    private static AuditPolicy.Decision decide(double confidence, Verdict... verdicts) {
        return AuditPolicy.decide(assessment(confidence, verdicts), 2, THRESHOLD, true);
    }

    private static Optional<CompletionCheck.Assessment> assessment(double confidence, Verdict... verdicts) {
        var items = new java.util.ArrayList<Item>();
        for (int i = 0; i < verdicts.length; i++) {
            items.add(new Item(i, verdicts[i], "evidence " + i));
        }
        return Optional.of(new CompletionCheck.Assessment(List.copyOf(items), confidence, "test-model"));
    }
}
