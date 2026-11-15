package dev.rashed.dispatch.technician;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

record UpdateTechnicianProfileRequest(
        @NotEmpty List<String> skills,
        @Min(1) @Max(Technician.MAX_SERVICE_RADIUS_KM) int serviceRadiusKm) {}
