package dev.rashed.dispatch.review;

import dev.rashed.dispatch.ai.CompletionCheck;
import java.util.Optional;

/**
 * Who decides a submitted job: code, from the AI's assessment (step 3.5, T31). The AI never approves anything
 * itself; this policy approves on its behalf only when everything points the same way, and otherwise a person
 * decides. It never rejects: a false approval pays for work not done and is hard to undo, while sending a good
 * job to a person costs a minute of their time. So every doubt goes to a person.
 */
final class AuditPolicy {

    enum Recommendation {
        APPROVE,
        REVIEW
    }

    record Decision(Recommendation recommendation, String reason) {}

    /**
     * @param threshold the confidence at or above which an all-MET assessment may approve on its own, chosen from
     *     the eval (evals/results/audit.md)
     */
    static Decision decide(
            Optional<CompletionCheck.Assessment> assessment, int photoCount, double threshold, boolean autoApprove) {
        if (photoCount == 0) {
            return review("No photos: nothing to check the checklist against.");
        }
        if (assessment.isEmpty()) {
            return review("The AI check couldn't run; a person checks the photos.");
        }
        var a = assessment.get();
        for (var item : a.items()) {
            if (item.verdict() == CompletionCheck.Verdict.NOT_MET) {
                return review(
                        "Item " + (item.position() + 1) + " looks not done: " + item.evidence()); // 1-based, as shown
            }
        }
        for (var item : a.items()) {
            if (item.verdict() == CompletionCheck.Verdict.UNCLEAR) {
                return review("Item " + (item.position() + 1) + " is unclear from the photos: " + item.evidence());
            }
        }
        var sure = Math.round(a.confidence() * 100);
        if (a.confidence() < threshold) {
            return review("Every item looks done, but the AI is only " + sure + "% sure (auto-approval needs "
                    + Math.round(threshold * 100) + "%).");
        }
        if (!autoApprove) {
            return review("Every item looks done (" + sure + "% sure); auto-approval is switched off.");
        }
        var shown = a.items().size() == 1
                ? "The checklist item is shown"
                : "All " + a.items().size() + " checklist items are shown";
        return new Decision(Recommendation.APPROVE, shown + " in the photos (" + sure + "% sure).");
    }

    private static Decision review(String reason) {
        return new Decision(Recommendation.REVIEW, reason);
    }

    private AuditPolicy() {}
}
