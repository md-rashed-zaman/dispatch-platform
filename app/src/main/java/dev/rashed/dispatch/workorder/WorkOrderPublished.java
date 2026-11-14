package dev.rashed.dispatch.workorder;

import dev.rashed.dispatch.shared.GeoPoint;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A work order is waiting for a technician. Carries what matching needs to search (site and skills), so the
 * listener doesn't have to read the work order back. Published after the publishing transaction commits.
 */
public record WorkOrderPublished(
        UUID workOrderId, UUID tenantId, GeoPoint site, List<String> requiredSkills, Instant occurredAt) {}
