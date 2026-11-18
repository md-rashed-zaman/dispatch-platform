package dev.rashed.dispatch.review;

import dev.rashed.dispatch.TestcontainersConfiguration;
import dev.rashed.dispatch.ai.CompletionCheck;
import dev.rashed.dispatch.tenancy.TenantContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * The completion audit eval (step 3.5, T31): 100 labeled jobs with photos from Wikimedia Commons (evals/audit). 35
 * are really done; the rest show the wrong thing, a near miss (parts not installed, the error still on screen), only
 * part of the checklist, misleading notes, a prompt injection in the notes, or no photo at all.
 *
 * <p>Measures the AI's verdicts and, more importantly, the policy built on them: for each threshold, how many jobs
 * would be approved without a person (should have been: true approvals; shouldn't: false approvals) and how many
 * would go to the review queue. The chosen threshold is the lowest with no false approval.
 *
 * <pre>
 * export $(grep ^GEMINI_API_KEY= ../.env)
 * ./mvnw test -Dtest=AuditEvals -DexcludedGroups=none
 * </pre>
 */
@Tag("eval")
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
        properties = {
            "dispatch.ai.replay.mode=record",
            "dispatch.ai.replay.directory=../evals/audit/recordings",
            "dispatch.ai.daily-calls-per-tenant=1000"
        })
class AuditEvals {

    static final double[] THRESHOLDS = {0.0, 0.5, 0.6, 0.7, 0.8, 0.85, 0.9, 0.95, 1.0};

    record Case(
            String id,
            String kind,
            String title,
            String description,
            List<String> checklist,
            @Nullable String notes,
            List<String> photos,
            boolean shouldApprove,
            List<CompletionCheck.Verdict> expectedItems) {}

    record Outcome(Case c, Optional<CompletionCheck.Assessment> assessment) {
        boolean allMet() {
            return assessment.isPresent()
                    && assessment.get().items().stream().allMatch(i -> i.verdict() == CompletionCheck.Verdict.MET);
        }

        boolean approvedAt(double threshold) {
            return !c.photos().isEmpty() && allMet() && assessment.get().confidence() >= threshold;
        }
    }

    @Autowired
    CompletionCheck check;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JsonMapper json;

    @Test
    void audit() throws Exception {
        var tenant = UUID.randomUUID();
        jdbc.update("insert into company (id, name, slug) values (?, 'Evals', ?)", tenant, "evals-" + tenant);
        var cases = List.of(json.readValue(Files.readString(Path.of("../evals/audit/cases.json")), Case[].class));

        var outcomes = new ArrayList<Outcome>();
        for (var c : cases) {
            if (c.photos().isEmpty()) {
                outcomes.add(new Outcome(c, Optional.empty())); // the policy sends these to a person, no model call
                continue;
            }
            var photos = new ArrayList<CompletionCheck.Photo>();
            for (var file : c.photos()) {
                photos.add(new CompletionCheck.Photo(
                        "image/jpeg", Files.readAllBytes(Path.of("../evals/audit/photos", file))));
            }
            var input = new CompletionCheck.Input(
                    c.title(),
                    c.description(),
                    c.notes(),
                    IntStream.range(0, c.checklist().size())
                            .mapToObj(i -> new CompletionCheck.ChecklistItem(
                                    i, c.checklist().get(i)))
                            .toList(),
                    photos);
            long started = System.nanoTime();
            outcomes.add(new Outcome(c, TenantContext.callAs(tenant, () -> check.check(input))));
            if (System.nanoTime() - started > 300_000_000L) {
                Thread.sleep(5_000); // a real call: stay under the free tier's 15 a minute
            }
        }
        report(outcomes);
    }

    private static void report(List<Outcome> outcomes) throws Exception {
        var out = new StringBuilder("# Completion audit eval, prompt ")
                .append(CompletionCheck.PROMPT_VERSION)
                .append("\n\n");
        out.append("Run ")
                .append(ZonedDateTime.now(ZoneId.of("Asia/Dhaka"))
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")))
                .append(" (Asia/Dhaka). ")
                .append(outcomes.size())
                .append(" jobs, ")
                .append(outcomes.stream().filter(o -> o.c().shouldApprove()).count())
                .append(" really done.\n\n");

        // Item verdicts: MET vs not (NOT_MET and UNCLEAR both mean "don't approve on this").
        int items = 0, itemsRight = 0, unanswered = 0;
        for (var o : outcomes) {
            if (o.c().photos().isEmpty()) continue;
            if (o.assessment().isEmpty()) {
                unanswered++;
                continue;
            }
            for (var item : o.assessment().get().items()) {
                var expected = o.c().expectedItems().get(item.position());
                items++;
                if ((item.verdict() == CompletionCheck.Verdict.MET) == (expected == CompletionCheck.Verdict.MET))
                    itemsRight++;
            }
        }
        out.append("Checklist items judged right (MET vs not): ")
                .append(itemsRight)
                .append(" / ")
                .append(items)
                .append(" (%.1f%%)".formatted(100.0 * itemsRight / items))
                .append(". Jobs with no usable answer: ")
                .append(unanswered)
                .append(".\n\n");

        out.append(
                "## The policy at each threshold\n\nApproved without a person when every item is MET and confidence >= threshold.\n\n");
        out.append(
                "| Threshold | True approvals | **False approvals** | Done, sent to a person | Not done, sent to a person | Automated |\n|---|---|---|---|---|---|\n");
        // The highest threshold that still automates as much as any threshold without a false approval: the
        // confidence gate then costs nothing on this data and adds a margin for data it hasn't seen.
        double chosen = -1;
        int bestAutomated = -1;
        for (var t : THRESHOLDS) {
            int tp = 0, fp = 0, fn = 0, tn = 0;
            for (var o : outcomes) {
                boolean approved = o.approvedAt(t);
                if (approved && o.c().shouldApprove()) tp++;
                else if (approved) fp++;
                else if (o.c().shouldApprove()) fn++;
                else tn++;
            }
            if (fp == 0 && tp >= bestAutomated) {
                bestAutomated = tp;
                chosen = t;
            }
            out.append("| %.2f | %d | %d | %d | %d | %.0f%% of done jobs |%n"
                    .formatted(t, tp, fp, fn, tn, 100.0 * tp / (tp + fn)));
        }
        out.append("\nChosen threshold: **")
                .append(chosen < 0 ? "none" : "%.2f".formatted(chosen))
                .append("**, the highest with no false approval that still automates the most done jobs.\n");

        out.append("\n## Confusion matrix at %.2f\n\n| | Approved by the policy | Sent to a person |\n|---|---|---|\n"
                .formatted(chosen));
        final double t = chosen;
        long tp = outcomes.stream()
                .filter(o -> o.c().shouldApprove() && o.approvedAt(t))
                .count();
        long fn = outcomes.stream()
                .filter(o -> o.c().shouldApprove() && !o.approvedAt(t))
                .count();
        long fp = outcomes.stream()
                .filter(o -> !o.c().shouldApprove() && o.approvedAt(t))
                .count();
        long tn = outcomes.stream()
                .filter(o -> !o.c().shouldApprove() && !o.approvedAt(t))
                .count();
        out.append("| **Really done** | ").append(tp).append(" | ").append(fn).append(" |\n");
        out.append("| **Not done** | ").append(fp).append(" | ").append(tn).append(" |\n");

        out.append(
                "\n## What went wrong, per job\n\nJobs where the AI's MET/not-MET differs from the label, and false approvals at 0.00.\n\n| Job | Kind | Expected | AI said | Confidence |\n|---|---|---|---|---|\n");
        for (var o : outcomes) {
            if (o.assessment().isEmpty()) continue;
            var said = o.assessment().get().items().stream()
                    .map(i -> i.verdict().name())
                    .toList();
            var expected = o.c().expectedItems().stream().map(Enum::name).toList();
            boolean itemWrong = IntStream.range(0, said.size())
                    .anyMatch(i -> said.get(i).equals("MET") != expected.get(i).equals("MET"));
            if (itemWrong || o.approvedAt(0) != o.c().shouldApprove()) {
                out.append("| ")
                        .append(o.c().id())
                        .append(" | ")
                        .append(o.c().kind())
                        .append(" | ")
                        .append(expected)
                        .append(" | ")
                        .append(said)
                        .append(": ")
                        .append(o.assessment()
                                .get()
                                .items()
                                .getFirst()
                                .evidence()
                                .replace("|", "/"))
                        .append(" | %.2f |%n".formatted(o.assessment().get().confidence()));
            }
        }
        System.out.println(out);
        Files.writeString(Path.of("../evals/results/audit.md"), out);
    }
}
