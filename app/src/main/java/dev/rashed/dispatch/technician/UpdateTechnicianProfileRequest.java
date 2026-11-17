package dev.rashed.dispatch.technician;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.jspecify.annotations.Nullable;

record UpdateTechnicianProfileRequest(
        @NotEmpty List<String> skills,
        @Min(1) @Max(Technician.MAX_SERVICE_RADIUS_KM) int serviceRadiusKm,
        @Nullable @Size(max = 500) String bio) {}
