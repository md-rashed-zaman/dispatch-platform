package dev.rashed.dispatch.ai;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "AI assistance", description = "Suggestions that help a dispatcher; the forms work without them")
@RestController
@RequestMapping("/api/ai")
class AiController {

    record SkillSuggestionRequest(
            @NotBlank @Size(max = 200) String title,
            @NotBlank @Size(max = 5000) String description) {}

    private final SkillSuggestions skillSuggestions;

    AiController(SkillSuggestions skillSuggestions) {
        this.skillSuggestions = skillSuggestions;
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
