package dev.rashed.dispatch.shared;

import java.security.SecureRandom;
import java.util.UUID;

/** UUID version 7 (RFC 9562): time-ordered, so new ids land at the end of a primary key index. */
public final class Uuids {

    private static final SecureRandom RANDOM = new SecureRandom();

    /** 48 bits of Unix milliseconds, version 7, then 74 random bits. */
    public static UUID v7() {
        var millis = System.currentTimeMillis();
        var mostSignificant = (millis << 16) | 0x7000L | (RANDOM.nextLong() & 0x0FFFL);
        var leastSignificant = (RANDOM.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L; // variant 10
        return new UUID(mostSignificant, leastSignificant);
    }

    private Uuids() {}
}
