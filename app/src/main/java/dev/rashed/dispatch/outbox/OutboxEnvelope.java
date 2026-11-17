package dev.rashed.dispatch.outbox;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What every event looks like on the wire, whatever its payload. Consumers deduplicate by {@code eventId}
 * (delivery is at least once), dispatch on {@code type} and {@code version}, and can tell tenants apart
 * without parsing the payload. {@code tenantId} is null for events that belong to no company (technicians).
 */
public record OutboxEnvelope(
        UUID eventId,
        String type,
        int version,
        Instant occurredAt,
        @Nullable UUID tenantId,
        String aggregateId,
        Object payload) {}
