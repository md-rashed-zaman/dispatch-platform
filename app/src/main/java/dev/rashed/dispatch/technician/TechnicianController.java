package dev.rashed.dispatch.technician;

import dev.rashed.dispatch.shared.CursorPage;
import dev.rashed.dispatch.shared.GeoPoint;
import dev.rashed.dispatch.shared.Skill;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Technicians", description = "Find technicians for a job")
@RestController
@RequestMapping("/api/technicians")
class TechnicianController {

    private final TechnicianSearch search;

    TechnicianController(TechnicianSearch search) {
        this.search = search;
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
