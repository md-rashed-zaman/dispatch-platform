package dev.rashed.dispatch.technician;

import java.util.UUID;

/** A technician's profile changed: anything holding a copy (the profile cache) must drop it. */
public record TechnicianUpdated(UUID technicianId) {}
