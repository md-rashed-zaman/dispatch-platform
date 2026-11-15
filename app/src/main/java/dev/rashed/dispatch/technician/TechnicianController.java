package dev.rashed.dispatch.technician;

import dev.rashed.dispatch.identity.CurrentUser;
import dev.rashed.dispatch.shared.CursorPage;
import dev.rashed.dispatch.shared.GeoPoint;
import dev.rashed.dispatch.shared.Skill;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Technicians", description = "Find technicians for a job")
@RestController
@RequestMapping("/api/technicians")
class TechnicianController {

    private final TechnicianSearch search;
    private final TechnicianProfiles profiles;

    TechnicianController(TechnicianSearch search, TechnicianProfiles profiles) {
        this.search = search;
        this.profiles = profiles;
    }

    @Operation(
            summary = "A technician's public profile",
            description = "Skills, service radius, rating. No contact" + " details or home location.")
    @GetMapping("/{id}")
    TechnicianProfile profile(@PathVariable UUID id) {
        return profiles.find(id);
    }

    @Operation(summary = "Update my skills and service radius (technicians)")
    @PutMapping("/me")
    TechnicianProfile updateMine(@Valid @RequestBody UpdateTechnicianProfileRequest request) {
        var technicianId = CurrentUser.current()
                .map(CurrentUser::technicianId)
                .orElseThrow(() -> new AccessDeniedException("Only a technician can update a technician profile"));
        return profiles.update(technicianId, request);
    }

    @Operation(
            summary = "Verified technicians with all the skills, within the radius, nearest first",
            description = "Only technicians whose own service radius reaches the site. Shows distance, never"
                    + " home locations. Pass \"next\" from the response as \"after\" to get the next page.")
    @GetMapping("/nearby")
    CursorPage<NearbyTechnician> nearby(
            @Parameter(description = "Site latitude, like 23.7925") @RequestParam @DecimalMin("-90") @DecimalMax("90")
                    double latitude,
            @Parameter(description = "Site longitude, like 90.4125")
                    @RequestParam
                    @DecimalMin("-180")
                    @DecimalMax("180")
                    double longitude,
            @Parameter(description = "Search radius around the site, 1 to 100 km")
                    @RequestParam(defaultValue = "20")
                    @Min(1)
                    @Max(Technician.MAX_SERVICE_RADIUS_KM)
                    int radiusKm,
            @Parameter(description = "Required skill; repeat for several. Technicians need all of them.")
                    @RequestParam
                    @Size(min = 1, max = 10)
                    List<@Pattern(regexp = Skill.PATTERN) String> skill,
            @Parameter(description = "Page size, 1 to 100") @RequestParam(defaultValue = "20") @Min(1) @Max(100)
                    int limit,
            @Parameter(description = "The \"next\" value from the previous page; leave out for the first page")
                    @RequestParam(required = false)
                    String after) {
        return search.nearby(new GeoPoint(latitude, longitude), radiusKm, skill, limit, after);
    }
}
