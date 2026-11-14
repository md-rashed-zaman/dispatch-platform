package dev.rashed.dispatch.workorder;

import java.time.Instant;
import java.util.UUID;

/** A work order waited too long for a technician, and the company is being told. */
public record SlaBreached(UUID workOrderId, UUID tenantId, String title, Instant waitingSince, Instant occurredAt) {}
