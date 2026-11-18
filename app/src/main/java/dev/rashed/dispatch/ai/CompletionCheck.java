package dev.rashed.dispatch.ai;

import jakarta.validation.Validator;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Does the proof match the checklist? A vision model looks at a submitted job's photos and gives each checklist
 * item a verdict with its evidence, plus an overall confidence (step 3.5, T31). It only recommends: the review
 * module's policy decides whether that's enough to approve without a person.
 *
 * <p>For system callers (the audit consumer). An answer that doesn't cover every checklist item exactly once is
 * treated like no answer: a person reviews.
 */
@Service
public class CompletionCheck {

    static final String FEATURE = "completion-audit";
    public static final String PROMPT_VERSION = "v1";

    public enum Verdict {
        MET,
        NOT_MET,
        UNCLEAR
    }

    public record ChecklistItem(int position, String description) {}

    public record Photo(String contentType, byte[] bytes) {}

    public record Input(
            String title,
            String description,
            @Nullable String notes,
            List<ChecklistItem> checklist,
            List<Photo> photos) {}

    /** One checklist item's verdict. */
    public record Item(int position, Verdict verdict, String evidence) {}

    /** The model's assessment, already checked for shape. */
    public record Assessment(List<Item> items, double confidence, String model) {}

    /** What the model must return. */
    record Answer(
            @NotNull List<@NotNull ItemAnswer> items,
            @NotNull @DecimalMin("0") @DecimalMax("1") Double confidence) {}

    record ItemAnswer(
            @NotNull Integer position,
            @NotNull Verdict verdict,
            @NotBlank String evidence) {}

    private final AiGateway gateway;
    private final Validator validator;

    CompletionCheck(AiGateway gateway, Validator validator) {
        this.gateway = gateway;
        this.validator = validator;
    }

    public Optional<Assessment> check(Input input) {
        var variables = new HashMap<String, Object>();
        variables.put("title", input.title());
        variables.put("description", input.description());
        variables.put("notes", input.notes() == null ? "(none)" : input.notes());
        variables.put("photoCount", input.photos().size());
        variables.put(
                "checklist",
                input.checklist().stream()
                        .map(item -> item.position() + ". " + item.description())
                        .collect(Collectors.joining("\n")));
        var images = input.photos().stream()
                .map(p -> new AiGateway.Image(p.contentType(), p.bytes()))
                .toList();

        var answer = gateway.ask(new AiGateway.Task<>(FEATURE, PROMPT_VERSION, variables, Answer.class, images));
        if (!(answer instanceof AiGateway.Answer.Answered<Answer>(var parsed, var model))) {
            return Optional.empty();
        }
        if (!validator.validate(parsed).isEmpty() || !coversEveryItemOnce(parsed, input.checklist())) {
            return Optional.empty();
        }
        var items = parsed.items().stream()
                .map(i -> new Item(i.position(), i.verdict(), i.evidence().strip()))
                .sorted(java.util.Comparator.comparingInt(Item::position))
                .toList();
        return Optional.of(new Assessment(items, parsed.confidence(), model));
    }

    private static boolean coversEveryItemOnce(Answer answer, List<ChecklistItem> checklist) {
        var expected = checklist.stream().map(ChecklistItem::position).sorted().toList();
        var got = answer.items().stream().map(ItemAnswer::position).sorted().toList();
        return expected.equals(got);
    }
}
