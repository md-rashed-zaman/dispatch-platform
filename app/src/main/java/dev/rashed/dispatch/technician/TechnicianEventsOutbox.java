package dev.rashed.dispatch.technician;

import dev.rashed.dispatch.outbox.Outbox;
import dev.rashed.dispatch.outbox.Topics;
import java.time.Instant;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Puts profile changes on Kafka, through the outbox (ADR 0006), so matching can re-embed the profile. */
@Component
class TechnicianEventsOutbox {

    private final Outbox outbox;

    TechnicianEventsOutbox(Outbox outbox) {
        this.outbox = outbox;
    }

    // Synchronous, inside the transaction that saves the technician: the event commits or rolls back with it.
    @EventListener
    void on(TechnicianUpdated event) {
        var now = Instant.now();
        outbox.append(
                Topics.TECHNICIAN_EVENTS,
                event.technicianId().toString(),
                TechnicianProfileChangedV1.TYPE,
                TechnicianProfileChangedV1.VERSION,
                null, // technicians belong to no company
                now,
                new TechnicianProfileChangedV1(event.technicianId(), now));
    }
}
