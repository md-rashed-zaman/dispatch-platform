package dev.rashed.dispatch.shared;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

/**
 * Where the next page of a list starts (keyset pagination, T13): the sort value and id of the last row the
 * client saw. Clients get it as an opaque string and send it back as {@code ?after=}. The id breaks ties
 * between rows with the same sort value, so no row is skipped or shown twice.
 */
public record Cursor(String sortValue, UUID id) {

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    public String encode() {
        return ENCODER.encodeToString((sortValue + "|" + id).getBytes(StandardCharsets.UTF_8));
    }

    /** @throws InvalidCursorException if the token wasn't made by {@link #encode()} */
    public static Cursor decode(String token) {
        try {
            var text = new String(DECODER.decode(token), StandardCharsets.UTF_8);
            var separator = text.lastIndexOf('|');
            return new Cursor(text.substring(0, separator), UUID.fromString(text.substring(separator + 1)));
        } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
            throw new InvalidCursorException();
        }
    }
}
