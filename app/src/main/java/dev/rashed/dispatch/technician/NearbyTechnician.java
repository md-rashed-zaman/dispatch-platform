package dev.rashed.dispatch.technician;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A search result. Shows how far away the technician lives, never where: a home location is personal data
 * (T14).
 */
public record NearbyTechnician(
        UUID id,
        String fullName,
        List<String> skills,
        @Nullable BigDecimal rating,
        double distanceKm) {}
