package dev.rashed.dispatch.ai;

import dev.rashed.dispatch.technician.SkillCatalog;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/**
 * "Which skills does this job need?" from a work order's title and description, chosen only from skills
 * technicians actually have. When AI is unavailable the answer is an empty list and {@code available = false}:
 * the dispatcher types the skills, as before.
 */
@Service
public class SkillSuggestions {

    static final String FEATURE = "skill-suggestions";
    static final String PROMPT_VERSION = "v1";
    private static final int MAX_SKILLS = 3;

    /** The model's answer, before we check it. */
    record Suggested(List<String> skills) {}

    /**
     * @param available false when no model could answer; the form works as before without suggestions
     */
    public record Suggestion(List<String> skills, boolean available) {}

    private final AiGateway gateway;
    private final SkillCatalog catalog;

    SkillSuggestions(AiGateway gateway, SkillCatalog catalog) {
        this.gateway = gateway;
        this.catalog = catalog;
    }

    @PreAuthorize("hasAnyRole('CLIENT_ADMIN', 'DISPATCHER')")
    public Suggestion suggest(String title, String description) {
        var known = catalog.codes();
        var answer = gateway.ask(new AiGateway.Task<>(
                FEATURE,
                PROMPT_VERSION,
                Map.of("title", title, "description", description, "skills", String.join(", ", known)),
                Suggested.class));
        return switch (answer) {
            // Model output is untrusted input (T27): keep only real codes, no duplicates, at most three.
            case AiGateway.Answer.Answered<Suggested>(var suggested, var model) ->
                new Suggestion(
                        suggested.skills() == null
                                ? List.of()
                                : suggested.skills().stream()
                                        .filter(known::contains)
                                        .distinct()
                                        .limit(MAX_SKILLS)
                                        .toList(),
                        true);
            case AiGateway.Answer.Unavailable<Suggested> unavailable -> new Suggestion(List.of(), false);
        };
    }
}
