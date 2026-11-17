package dev.rashed.dispatch.ai;

import dev.rashed.dispatch.shared.Skill;
import dev.rashed.dispatch.shared.TimeWindow;
import dev.rashed.dispatch.technician.SkillCatalog;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * "Gulshan office printer shows E05, need someone tomorrow morning" -> a work order draft (step 3.3, T29).
 *
 * <p>The model's answer is untrusted input: it is checked with Bean Validation and the rules a real work order
 * must meet. If it fails, the model gets one more try with the errors listed; if that fails too, the valid parts
 * become a partial draft and {@code missing} names what the dispatcher still has to fill. Nothing is created:
 * the draft fills the form, and a person submits it.
 */
@Service
public class IntakeParser {

    static final String FEATURE = "intake";
    static final String PROMPT_VERSION = "v2";
    static final ZoneId DHAKA = ZoneId.of("Asia/Dhaka");
    private static final DateTimeFormatter NOW =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm, EEEE", Locale.ENGLISH);
    private static final Duration FURTHEST_START = Duration.ofDays(60);

    enum Urgency {
        NORMAL,
        URGENT
    }

    /** What the model must return; the annotations are the first check on its answer. */
    record Parsed(
            @NotBlank @Size(max = 200) String title,
            @NotNull List<@NotNull @Pattern(regexp = Skill.PATTERN) String> skills,
            @NotNull Urgency urgency,
            @Size(max = 500) String siteAddress,
            @NotNull LocalDateTime startsAt,
            @NotNull LocalDateTime endsAt,
            @NotEmpty @Size(max = 5) List<@NotBlank @Size(max = 300) String> checklist) {}

    /** Field names match the New work order form; anything not filled is null or empty. */
    public record Draft(
            String title,
            String description,
            List<String> skills,
            Urgency urgency,
            String siteAddress,
            TimeWindow timeWindow,
            List<String> checklist) {}

    /**
     * @param missing form fields the dispatcher must still fill; the site location (latitude, longitude) is always
     *     among them, because guessing coordinates is not a language model's job
     * @param available false when no model answered; the draft then holds only the description
     */
    public record Result(Draft draft, List<String> missing, boolean available) {}

    private final AiGateway gateway;
    private final SkillCatalog catalog;
    private final Validator validator;
    private final JsonMapper json;

    IntakeParser(AiGateway gateway, SkillCatalog catalog, Validator validator, JsonMapper json) {
        this.gateway = gateway;
        this.catalog = catalog;
        this.validator = validator;
        this.json = json;
    }

    @PreAuthorize("hasAnyRole('CLIENT_ADMIN', 'DISPATCHER')")
    public Result draft(String text) {
        return draft(text, ZonedDateTime.now(DHAKA));
    }

    /** With an explicit "now", so evals are repeatable. */
    Result draft(String text, ZonedDateTime now) {
        var known = catalog.codes();
        var input = new HashMap<String, Object>();
        input.put("text", text);
        input.put("now", now.withZoneSameInstant(DHAKA).format(NOW));
        input.put("skills", String.join(", ", known));
        input.put("corrections", "none");

        var first = ask(input);
        if (first == null) {
            return new Result(
                    partial(null, text, known, now),
                    List.of("title", "skills", "siteAddress", "timeWindow", "checklist", "siteLocation"),
                    false);
        }
        var errors = problems(first, known, now);
        if (errors.isEmpty()) {
            return result(first, text, known, now);
        }
        // One more try, with exactly what was wrong (T29: feed validation errors back).
        input.put(
                "corrections",
                "Your previous answer was " + json.writeValueAsString(first) + ". It has these problems, fix them: "
                        + String.join("; ", errors) + ".");
        var second = ask(input);
        var better = second != null && problems(second, known, now).size() < errors.size() ? second : first;
        return result(better, text, known, now);
    }

    private Parsed ask(Map<String, Object> input) {
        var answer = gateway.ask(new AiGateway.Task<>(FEATURE, PROMPT_VERSION, Map.copyOf(input), Parsed.class));
        return answer instanceof AiGateway.Answer.Answered<Parsed>(var parsed, var model) ? parsed : null;
    }

    /** Everything wrong with an answer, as "field: problem", in words the model can act on. */
    List<String> problems(Parsed parsed, Set<String> known, ZonedDateTime now) {
        var errors = new ArrayList<String>();
        validator.validate(parsed).forEach(v -> errors.add(v.getPropertyPath() + ": " + v.getMessage()));
        if (parsed.skills() != null) {
            parsed.skills().stream()
                    .filter(code -> code != null && !known.contains(code))
                    .forEach(code -> errors.add("skills: '" + code + "' is not on the list"));
        }
        var window = window(parsed);
        if (parsed.startsAt() != null && parsed.endsAt() != null && window == null) {
            errors.add("endsAt: must be after startsAt");
        }
        if (window != null && window.startsAt().isBefore(now.toInstant().minus(Duration.ofMinutes(5)))) {
            errors.add("startsAt: is in the past");
        }
        if (window != null && window.startsAt().isAfter(now.toInstant().plus(FURTHEST_START))) {
            errors.add("startsAt: is more than 60 days ahead");
        }
        return errors;
    }

    private Result result(Parsed parsed, String text, Set<String> known, ZonedDateTime now) {
        var draft = partial(parsed, text, known, now);
        var missing = new ArrayList<String>();
        if (draft.title() == null) missing.add("title");
        if (draft.skills().isEmpty()) missing.add("skills");
        if (draft.siteAddress() == null) missing.add("siteAddress");
        if (draft.timeWindow() == null) missing.add("timeWindow");
        if (draft.checklist().isEmpty()) missing.add("checklist");
        missing.add("siteLocation");
        return new Result(draft, missing, true);
    }

    /** Keeps each field only if it is valid on its own; an answer with one bad field still helps with the rest. */
    private Draft partial(Parsed parsed, String text, Set<String> known, ZonedDateTime now) {
        var description = text.strip();
        if (parsed == null) {
            return new Draft(null, description, List.of(), null, null, null, List.of());
        }
        var title = parsed.title() != null
                        && !parsed.title().isBlank()
                        && parsed.title().length() <= 200
                ? parsed.title().strip()
                : null;
        var skills = parsed.skills() == null
                ? List.<String>of()
                : parsed.skills().stream()
                        .filter(known::contains)
                        .distinct()
                        .limit(3)
                        .toList();
        var address = parsed.siteAddress() != null
                        && !parsed.siteAddress().isBlank()
                        && parsed.siteAddress().length() <= 500
                ? parsed.siteAddress().strip()
                : null;
        var window = window(parsed);
        boolean windowOk = window != null
                && !window.startsAt().isBefore(now.toInstant().minus(Duration.ofMinutes(5)))
                && !window.startsAt().isAfter(now.toInstant().plus(FURTHEST_START));
        var checklist = parsed.checklist() == null
                ? List.<String>of()
                : parsed.checklist().stream()
                        .filter(item -> item != null && !item.isBlank() && item.length() <= 300)
                        .map(String::strip)
                        .limit(5)
                        .toList();
        return new Draft(title, description, skills, parsed.urgency(), address, windowOk ? window : null, checklist);
    }

    /** The model answers in Dhaka local time; work orders store instants. Null if it isn't a real window. */
    private static TimeWindow window(Parsed parsed) {
        if (parsed.startsAt() == null
                || parsed.endsAt() == null
                || !parsed.endsAt().isAfter(parsed.startsAt())) {
            return null;
        }
        return new TimeWindow(
                parsed.startsAt().atZone(DHAKA).toInstant(),
                parsed.endsAt().atZone(DHAKA).toInstant());
    }
}
