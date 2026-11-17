package dev.rashed.dispatch.ai;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "AI assistance", description = "Suggestions that help a dispatcher; the forms work without them")
@RestController
class IntakeController {

    record DraftRequest(@NotBlank @Size(max = 2000) String text) {}

    private final IntakeParser intake;

    IntakeController(IntakeParser intake) {
        this.intake = intake;
    }

    /**
     * Always 200, and never creates anything: the draft fills the New work order form, which a person checks
     * and submits. {@code missing} lists what's left to fill; {@code available: false} means no model answered.
     */
    @Operation(
            summary =
                    "Draft a work order from a message, like \"Gulshan printer shows E05, need someone tomorrow morning\"")
    @PostMapping("/api/work-orders/draft-from-text")
    IntakeParser.Result draftFromText(@Valid @RequestBody DraftRequest request) {
        return intake.draft(request.text());
    }
}
