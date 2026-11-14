package dev.rashed.dispatch.media;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Permission to upload one file: send its bytes with {@code method} to {@code url}, with exactly these
 * {@code headers}, before {@code expiresAt}. Then confirm the attachment.
 */
public record UploadTicket(
        UUID attachmentId, String method, String url, Map<String, String> headers, Instant expiresAt) {}
