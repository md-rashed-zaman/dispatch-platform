package dev.rashed.dispatch.technician;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** What anyone signed in may see about a technician: no phone number, no home location. Cached (T23). */
public record TechnicianProfile(
        UUID id,
        String fullName,
        List<String> skills,
        int serviceRadiusKm,
        @Nullable BigDecimal rating,
        TechnicianStatus status) {

    static TechnicianProfile of(Technician technician) {
        return new TechnicianProfile(
                technician.id(),
                technician.fullName(),
                technician.skills().stream().map(s -> s.code()).sorted().toList(),
                technician.serviceRadiusKm(),
                technician.rating(),
                technician.status());
    }
}
