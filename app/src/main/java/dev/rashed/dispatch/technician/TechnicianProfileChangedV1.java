package dev.rashed.dispatch.technician;

import java.time.Instant;
import java.util.UUID;

/**
 * On {@code dispatch.technician.events.v1}: a technician's profile changed. Carries only the id: a consumer
 * reads the current profile, so an old event delivered late can't bring back old data.
 */
public record TechnicianProfileChangedV1(UUID technicianId, Instant occurredAt) {

    public static final String TYPE = "technician.profile-changed";
    public static final int VERSION = 1;
}
