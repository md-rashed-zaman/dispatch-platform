package dev.rashed.dispatch.notification;

import dev.rashed.dispatch.outbox.Topics;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Pushes every work order change (and SLA breach) to the company's open dashboards over server-sent events (ADR 0007, T21).
 *
 * <p>Each app instance holds only its own connections, so each instance must see every event: the listener
 * joins a consumer group of its own (a random group id per instance) and starts at the latest offset. A
 * dashboard that reconnects reloads the list once; live updates are for "now", not a replay of history.
 */
@Component
class LiveUpdates {

    private static final Logger log = LoggerFactory.getLogger(LiveUpdates.class);

    private final Map<UUID, Set<SseEmitter>> dashboards = new ConcurrentHashMap<>();
    private final WorkOrderEventReader events;
    private final Duration connectionTimeout;

    LiveUpdates(
            WorkOrderEventReader events,
            @Value("${dispatch.live.connection-timeout:PT30M}") Duration connectionTimeout) {
        this.events = events;
        this.connectionTimeout = connectionTimeout;
    }

    SseEmitter subscribe(UUID tenantId) {
        // Closed after the timeout on purpose: the browser reconnects, and a load balancer can move it.
        var emitter = new SseEmitter(connectionTimeout.toMillis());
        var forTenant = dashboards.computeIfAbsent(tenantId, id -> ConcurrentHashMap.newKeySet());
        forTenant.add(emitter);
        Runnable remove = () -> forTenant.remove(emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(error -> remove.run());
        // Sent at once, so proxies and the browser see the stream open; retry tells the browser how long to wait
        // before reconnecting.
        send(emitter, SseEmitter.event().comment("connected").reconnectTime(3_000));
        return emitter;
    }

    @KafkaListener(
            id = "live-updates",
            groupId = "dispatch-live-#{T(java.util.UUID).randomUUID()}",
            topics = Topics.WORK_ORDER_EVENTS,
            autoStartup = "${dispatch.notification.consumer-enabled:true}",
            properties = "auto.offset.reset=latest")
    void on(ConsumerRecord<String, String> record) {
        // Best effort: never throws, so nothing is retried or dead-lettered here. The notification consumer
        // already dead-letters messages it can't read.
        try {
            var event = events.read(record.value());
            var open = event == null ? null : dashboards.get(event.tenantId());
            if (open == null || open.isEmpty()) {
                return;
            }
            var update = SseEmitter.event()
                    .id(event.eventId().toString())
                    .name(event.type())
                    .data(event.payload(), MediaType.APPLICATION_JSON);
            // shortcut: sends on the consumer thread, so a client that stops reading slows the others once its
            // buffer is full; hand each connection its own queue when there are many dashboards.
            open.forEach(emitter -> send(emitter, update));
        } catch (RuntimeException e) {
            log.debug("Live update skipped: {}", e.toString());
        }
    }

    /** A comment every 20 s keeps idle connections from being closed by proxies, and finds dead ones. */
    @Scheduled(fixedRate = 20_000)
    void keepAlive() {
        dashboards
                .values()
                .forEach(open ->
                        open.forEach(emitter -> send(emitter, SseEmitter.event().comment("keep-alive"))));
    }

    private void send(SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
        } catch (IOException | IllegalStateException gone) {
            emitter.completeWithError(gone); // the browser left; onError removes it
        }
    }
}
