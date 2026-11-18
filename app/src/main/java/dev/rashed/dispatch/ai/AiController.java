package dev.rashed.dispatch.ai;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "AI assistance", description = "Suggestions that help a dispatcher; the forms work without them")
@RestController
@RequestMapping("/api/ai")
class AiController {

    record SkillSuggestionRequest(
            @NotBlank @Size(max = 200) String title,
            @NotBlank @Size(max = 5000) String description) {}

    private final SkillSuggestions skillSuggestions;
    private final AiUsage usage;

    AiController(SkillSuggestions skillSuggestions, AiUsage usage) {
        this.skillSuggestions = skillSuggestions;
        this.usage = usage;
    }

    @Operation(
            summary =
                    "The company's AI calls and their cost at list price, per UTC day and per feature (company admins)")
    @GetMapping("/usage")
    AiUsage.Usage usage(@RequestParam(defaultValue = "30") @Min(1) @Max(90) int days) {
        return usage.lastDays(days);
    }

    /**
     * Always 200: when AI is unavailable the answer is {@code available: false} with no skills, because a
     * missing suggestion isn't an error for the person filling the form.
     */
    @Operation(summary = "Suggest skills for a work order from its title and description")
    @PostMapping("/skill-suggestions")
    SkillSuggestions.Suggestion skillSuggestions(@Valid @RequestBody SkillSuggestionRequest request) {
        return skillSuggestions.suggest(request.title(), request.description());
    }
}
