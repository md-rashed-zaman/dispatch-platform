package dev.rashed.dispatch.workorder;

import java.time.Instant;
import java.util.UUID;

/** A technician got the job (accepted an offer first). */
public record WorkOrderAssigned(UUID workOrderId, UUID tenantId, UUID technicianId, String title, Instant occurredAt) {}
