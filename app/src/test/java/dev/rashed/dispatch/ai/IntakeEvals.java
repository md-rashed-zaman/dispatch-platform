package dev.rashed.dispatch.ai;

import static org.mockito.BDDMockito.given;

import dev.rashed.dispatch.TestcontainersConfiguration;
import dev.rashed.dispatch.technician.SkillCatalog;
import dev.rashed.dispatch.tenancy.TenantContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

/**
 * The intake eval (step 3.3, T29): 50 messages in English, Banglish and Bangla with what a good draft looks like.
 * Not a unit test: the model is real and answers vary, so it reports accuracy per field instead of pass/fail.
 *
 * <p>Run it on purpose, never in the normal build (it's tagged {@code eval}):
 *
 * <pre>
 * export $(grep ^GEMINI_API_KEY= ../.env)
 * ./mvnw test -Dtest=IntakeEvals -DexcludedGroups=none
 * </pre>
 *
 * Every answer is recorded in {@code evals/intake/recordings}, keyed by prompt version and the exact prompt, so a
 * re-run replays them and costs nothing; a new prompt version is a new key and calls the model again. Results go
 * to {@code evals/results/}.
 */
@Tag("eval")
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
        properties = {
            "dispatch.ai.replay.mode=record",
            "dispatch.ai.replay.directory=../evals/intake/recordings",
            "dispatch.ai.daily-calls-per-tenant=1000"
        })
class IntakeEvals {

    /** The skills of the local sample data, fixed so results don't depend on what's in the database. */
    static final List<String> CATALOG = List.of(
            "ac-repair",
            "access-control",
            "atm",
            "cctv",
            "electrical",
            "generator",
            "networking",
            "pos-terminal",
            "printer",
            "solar");

    static final List<String> FIELDS = List.of("skills", "date", "window", "urgency", "address", "complete");

    record Expect(
            List<String> skills,
            List<String> alsoOk,
            LocalDate date,
            String window,
            IntakeParser.Urgency urgency,
            List<String> address) {}

    record Case(String id, String lang, LocalDateTime now, String text, Expect expect) {}

    @MockitoBean
    SkillCatalog catalog;

    @Autowired
    IntakeParser intake;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JsonMapper json;

    @Test
    void intake() throws Exception {
        given(catalog.codes()).willReturn(new TreeSet<>(CATALOG));
        var tenant = UUID.randomUUID();
        jdbc.update("insert into company (id, name, slug) values (?, 'Evals', ?)", tenant, "evals-" + tenant);

        var cases = Files.readAllLines(Path.of("../evals/intake/cases.jsonl")).stream()
                .filter(line -> !line.isBlank())
                .map(line -> json.readValue(line, Case.class))
                .toList();

        // field -> [right, scored]; also per language
        var totals = new LinkedHashMap<String, int[]>();
        var byLanguage = new TreeMap<String, int[]>();
        var failures = new ArrayList<String>();
        for (var c : cases) {
            long started = System.nanoTime();
            var now = c.now().atZone(IntakeParser.DHAKA);
            var result = TenantContext.callAs(tenant, () -> intake.draft(c.text(), now));
            var scores = score(c, result, now);
            scores.forEach((field, right) -> {
                if (right == null) return;
                var total = totals.computeIfAbsent(field, f -> new int[2]);
                total[0] += right ? 1 : 0;
                total[1]++;
                var language = byLanguage.computeIfAbsent(c.lang(), l -> new int[2]);
                language[0] += right ? 1 : 0;
                language[1]++;
                if (!right)
                    failures.add("| " + c.id() + " | " + field + " | " + expected(c, field) + " | " + got(result, field)
                            + " |");
            });
            // Calls that really went to the model are paced under the free tier's 15 a minute; replays aren't.
            if (Duration.ofNanos(System.nanoTime() - started).toMillis() > 300) {
                Thread.sleep(5_000);
            }
        }
        report(cases.size(), totals, byLanguage, failures);
    }

    /** Right or wrong per field; null when the case doesn't score that field (its answer is open). */
    private Map<String, Boolean> score(Case c, IntakeParser.Result result, ZonedDateTime now) {
        var draft = result.draft();
        var e = c.expect();
        var scores = new LinkedHashMap<String, Boolean>();

        var allowed = new HashSet<>(e.skills());
        allowed.addAll(e.alsoOk());
        scores.put("skills", draft.skills().containsAll(e.skills()) && allowed.containsAll(draft.skills()));

        var start = draft.timeWindow() == null
                ? null
                : draft.timeWindow().startsAt().atZone(IntakeParser.DHAKA);
        var end =
                draft.timeWindow() == null ? null : draft.timeWindow().endsAt().atZone(IntakeParser.DHAKA);
        scores.put(
                "date",
                e.date() == null ? null : start != null && start.toLocalDate().equals(e.date()));
        scores.put("window", e.window() == null ? null : start != null && inWindow(e.window(), start, end, now));
        scores.put("urgency", e.urgency() == null ? null : e.urgency() == draft.urgency());
        if (e.address() == null) {
            scores.put("address", null);
        } else if (e.address().isEmpty()) {
            scores.put("address", draft.siteAddress() == null);
        } else {
            scores.put(
                    "address",
                    draft.siteAddress() != null
                            && e.address().stream()
                                    .anyMatch(part ->
                                            draft.siteAddress().toLowerCase().contains(part.toLowerCase())));
        }
        // A draft a dispatcher can use as is: everything filled except what the message didn't say.
        scores.put(
                "complete",
                result.available()
                        && draft.title() != null
                        && draft.timeWindow() != null
                        && !draft.checklist().isEmpty());
        return scores;
    }

    /** The part of day the message asked for, by start hour in Dhaka. */
    private static boolean inWindow(String expected, ZonedDateTime start, ZonedDateTime end, ZonedDateTime now) {
        int hour = start.getHour();
        return switch (expected) {
            case "morning" -> hour >= 8 && hour < 12;
            case "afternoon" -> hour >= 12 && hour < 17;
            case "evening" -> hour >= 17 && hour < 21;
            case "day" -> hour >= 8 && hour <= 10 && end.getHour() >= 15;
            case "urgent" -> !start.isBefore(now.minusMinutes(5)) && !start.isAfter(now.plusHours(2));
            default -> throw new IllegalArgumentException("Unknown window " + expected);
        };
    }

    private static String expected(Case c, String field) {
        var e = c.expect();
        return switch (field) {
            case "skills" -> e.skills() + (e.alsoOk().isEmpty() ? "" : " (also ok: " + e.alsoOk() + ")");
            case "date" -> String.valueOf(e.date());
            case "window" -> e.window();
            case "urgency" -> String.valueOf(e.urgency());
            case "address" -> e.address().isEmpty() ? "none" : String.join(" or ", e.address());
            default -> "a usable draft";
        };
    }

    private static String got(IntakeParser.Result result, String field) {
        var d = result.draft();
        var window = d.timeWindow() == null
                ? "none"
                : d.timeWindow().startsAt().atZone(IntakeParser.DHAKA).toLocalDateTime() + " to "
                        + d.timeWindow().endsAt().atZone(IntakeParser.DHAKA).toLocalTime();
        return switch (field) {
            case "skills" -> d.skills().toString();
            case "date", "window" -> window;
            case "urgency" -> String.valueOf(d.urgency());
            case "address" -> String.valueOf(d.siteAddress());
            default -> result.available() ? "missing " + result.missing() : "no model answered";
        };
    }

    private static void report(
            int count, Map<String, int[]> totals, Map<String, int[]> byLanguage, List<String> failures)
            throws IOException {
        var out = new StringBuilder();
        out.append("# Intake eval, prompt ").append(IntakeParser.PROMPT_VERSION).append("\n\n");
        out.append("Run ")
                .append(ZonedDateTime.now(IntakeParser.DHAKA).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")))
                .append(" (Asia/Dhaka), ")
                .append(count)
                .append(" cases.\n\n");
        out.append("| Field | Accuracy | Right / scored |\n|---|---|---|\n");
        int right = 0, scored = 0;
        for (var field : FIELDS) {
            var t = totals.getOrDefault(field, new int[2]);
            out.append(row(field, t));
            right += t[0];
            scored += t[1];
        }
        out.append(row("**all fields**", new int[] {right, scored}));
        out.append("\n| Language | Accuracy | Right / scored |\n|---|---|---|\n");
        byLanguage.forEach((language, t) -> out.append(row(language, t)));
        out.append("\n## Wrong answers\n\n| Case | Field | Expected | Got |\n|---|---|---|---|\n");
        failures.forEach(line -> out.append(line).append("\n"));

        System.out.println(out);
        var file = Path.of("../evals/results/intake-" + IntakeParser.PROMPT_VERSION + ".md");
        Files.createDirectories(file.getParent());
        Files.writeString(file, out);
    }

    private static String row(String name, int[] t) {
        return "| %s | %.1f%% | %d / %d |%n".formatted(name, t[1] == 0 ? 0.0 : 100.0 * t[0] / t[1], t[0], t[1]);
    }
}
