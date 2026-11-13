package dev.rashed.dispatch.shared;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

public final class Timestamps {

    /**
     * Now, at Postgres's precision (microseconds), so the value in memory equals what gets stored and
     * read back. Entities set their creation time with this in their factory instead of Hibernate's
     * {@code @CreationTimestamp}, which Hibernate 7 then reports as changed, causing an extra full UPDATE
     * right after every INSERT.
     */
    public static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    private Timestamps() {}
}
