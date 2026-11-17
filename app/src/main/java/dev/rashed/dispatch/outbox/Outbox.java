package dev.rashed.dispatch.outbox;

import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Appends events for the relay to send. Call it from inside the transaction that makes the change. */
@Service
public class Outbox {

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final Tracer tracer;
    private final Propagator propagator;

    Outbox(JdbcClient jdbc, JsonMapper json, Tracer tracer, Propagator propagator) {
        this.jdbc = jdbc;
        this.json = json;
        this.tracer = tracer;
        this.propagator = propagator;
    }

    /**
     * Stores the event in the caller's transaction, so it's saved exactly when the change is. MANDATORY:
     * without a transaction there would be nothing to be atomic with, so that's a bug, and it fails loudly.
     *
     * @param key Kafka message key: events with the same key keep their order (use the aggregate id)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID append(
            String topic,
            String key,
            String type,
            int version,
            @Nullable UUID tenantId,
            Instant occurredAt,
            Object payload) {
        var envelope = new OutboxEnvelope(UUID.randomUUID(), type, version, occurredAt, tenantId, key, payload);
        jdbc.sql("""
                        insert into outbox_event (event_id, topic, message_key, event_type, payload, trace_parent)
                        values (:eventId, :topic, :key, :type, cast(:payload as jsonb), :traceParent)
                        """)
                .param("eventId", envelope.eventId())
                .param("topic", topic)
                .param("key", key)
                .param("type", type)
                .param("payload", json.writeValueAsString(envelope))
                .param("traceParent", currentTraceParent())
                .update();
        return envelope.eventId();
    }

    /** The current trace as a W3C traceparent header value, or null outside a trace. */
    private @Nullable String currentTraceParent() {
        var span = tracer.currentSpan();
        if (span == null) {
            return null;
        }
        var carrier = new HashMap<String, String>();
        propagator.inject(span.context(), carrier, Map::put);
        return carrier.get("traceparent");
    }
}
