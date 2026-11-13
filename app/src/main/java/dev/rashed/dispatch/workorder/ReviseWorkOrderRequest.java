package dev.rashed.dispatch.workorder;

import dev.rashed.dispatch.shared.Skill;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Set;

/** A partial edit of a draft. Leave a field out (or null) to keep its current value. */
public record ReviseWorkOrderRequest(
        @Size(max = 200) @Pattern(regexp = NOT_BLANK) String title,
        @Size(max = 5000) @Pattern(regexp = NOT_BLANK) String description,
        @Size(max = 500) @Pattern(regexp = NOT_BLANK) String siteAddress,
        @Valid CreateWorkOrderRequest.Site site,
        @Size(min = 1, max = 20) Set<@NotNull @Pattern(regexp = Skill.PATTERN) String> requiredSkills,
        @Valid CreateWorkOrderRequest.Window timeWindow,
        @Valid CreateWorkOrderRequest.Budget budget) {

    /** At least one non-space character (null passes: it means "unchanged"). */
    static final String NOT_BLANK = "(?s).*\\S.*";

    WorkOrderChanges toChanges() {
        return new WorkOrderChanges(
                title,
                description,
                siteAddress,
                site == null ? null : site.toGeoPoint(),
                requiredSkills,
                timeWindow == null ? null : timeWindow.toTimeWindow(),
                budget == null ? null : budget.toMoney());
    }
}
