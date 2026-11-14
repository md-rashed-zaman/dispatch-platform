package dev.rashed.dispatch.outbox;

import java.time.Instant;
import java.util.UUID;

/**
 * What every event looks like on the wire, whatever its payload. Consumers deduplicate by {@code eventId}
 * (delivery is at least once), dispatch on {@code type} and {@code version}, and can tell tenants apart
 * without parsing the payload.
 */
public record OutboxEnvelope(
        UUID eventId,
        String type,
        int version,
        Instant occurredAt,
        UUID tenantId,
        String aggregateId,
        Object payload) {}
