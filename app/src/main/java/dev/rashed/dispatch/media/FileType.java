package dev.rashed.dispatch.media;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;

/**
 * The file types a work order accepts, each with the first bytes every real file of that type starts with.
 * A client can claim any content type; the bytes show what it actually uploaded.
 */
enum FileType {
    JPEG("image/jpeg", "jpg", bytes(0xFF, 0xD8, 0xFF)),
    PNG("image/png", "png", bytes(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)),
    PDF("application/pdf", "pdf", "%PDF-".getBytes(StandardCharsets.US_ASCII));

    /** How many leading bytes {@link #matches} needs. */
    static final int SIGNATURE_LENGTH = 8;

    final String contentType;
    final String extension;
    private final byte[] signature;

    FileType(String contentType, String extension, byte[] signature) {
        this.contentType = contentType;
        this.extension = extension;
        this.signature = signature;
    }

    static Optional<FileType> of(String contentType) {
        return Arrays.stream(values())
                .filter(t -> t.contentType.equals(contentType))
                .findFirst();
    }

    boolean matches(byte[] firstBytes) {
        return firstBytes.length >= signature.length
                && Arrays.equals(Arrays.copyOf(firstBytes, signature.length), signature);
    }

    private static byte[] bytes(int... values) {
        var result = new byte[values.length];
        for (var i = 0; i < values.length; i++) {
            result[i] = (byte) values[i];
        }
        return result;
    }
}
