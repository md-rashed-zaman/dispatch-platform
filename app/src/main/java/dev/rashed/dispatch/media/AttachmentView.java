package dev.rashed.dispatch.media;

import java.time.Instant;
import java.util.UUID;

/** A confirmed attachment, with a short-lived link to download it (the bucket itself is private). */
public record AttachmentView(
        UUID id,
        String fileName,
        String contentType,
        long size,
        Instant uploadedAt,
        String downloadUrl,
        Instant downloadUrlExpiresAt) {}
