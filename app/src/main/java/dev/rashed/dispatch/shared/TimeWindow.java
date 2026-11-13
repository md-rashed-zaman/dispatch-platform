package dev.rashed.dispatch.shared;

import jakarta.persistence.Embeddable;
import java.time.Instant;
import java.util.Objects;

/** When work may happen. Always UTC instants; convert to Asia/Dhaka only for display. */
@Embeddable
public record TimeWindow(Instant startsAt, Instant endsAt) {

    public TimeWindow {
        Objects.requireNonNull(startsAt, "startsAt");
        Objects.requireNonNull(endsAt, "endsAt");
        if (!endsAt.isAfter(startsAt)) {
            throw new IllegalArgumentException("Time window must end after it starts");
        }
    }
}
