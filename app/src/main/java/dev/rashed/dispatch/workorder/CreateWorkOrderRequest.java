package dev.rashed.dispatch.workorder;

import com.fasterxml.jackson.annotation.JsonIgnore;
import dev.rashed.dispatch.shared.GeoPoint;
import dev.rashed.dispatch.shared.Money;
import dev.rashed.dispatch.shared.Skill;
import dev.rashed.dispatch.shared.TimeWindow;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * What a client sends to create a work order. No status, tenant or technician fields: the server decides
 * those, so a client can't send {@code "status": "APPROVED"} (unknown fields are ignored).
 */
public record CreateWorkOrderRequest(
        @NotBlank @Size(max = 200) String title,
        @NotBlank @Size(max = 5000) String description,
        @NotBlank @Size(max = 500) String siteAddress,
        @NotNull @Valid Site site,
        @NotEmpty @Size(max = 20) Set<@NotNull @Pattern(regexp = Skill.PATTERN) String> requiredSkills,
        @NotNull @Valid Window timeWindow,
        @NotNull @Valid Budget budget,
        @Size(max = WorkOrder.MAX_CHECKLIST_ITEMS) List<@NotBlank @Size(max = 500) String> checklist) {

    public List<String> checklist() {
        return checklist == null ? List.of() : checklist;
    }

    public record Site(
            @NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
            @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude) {

        GeoPoint toGeoPoint() {
            return new GeoPoint(latitude, longitude);
        }
    }

    public record Window(@NotNull Instant startsAt, @NotNull Instant endsAt) {

        @JsonIgnore // a validation rule, not a JSON field
        @AssertTrue(message = "must end after it starts")
        public boolean isEndAfterStart() {
            return startsAt == null || endsAt == null || endsAt.isAfter(startsAt);
        }

        TimeWindow toTimeWindow() {
            return new TimeWindow(startsAt, endsAt);
        }
    }

    /** Minor units (poisha for BDT), never a decimal amount. */
    public record Budget(
            @NotNull @Positive Long minor,
            @NotNull @Pattern(regexp = "[A-Z]{3}") String currency) {

        Money toMoney() {
            return new Money(minor, currency);
        }
    }
}
