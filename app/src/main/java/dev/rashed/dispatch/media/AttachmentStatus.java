package dev.rashed.dispatch.media;

public enum AttachmentStatus {
    /** An upload URL was handed out; the file may or may not have arrived. Invisible to everyone. */
    PENDING,
    /** The file arrived and passed the checks. */
    CONFIRMED
}
