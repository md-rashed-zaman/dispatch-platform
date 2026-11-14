package dev.rashed.dispatch.media;

import dev.rashed.dispatch.shared.Timestamps;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/** A file attached to a work order. The bytes live in object storage under {@link #objectKey}. */
@Entity
@Table(name = "attachment")
class Attachment {

    @Id
    private UUID id;

    @TenantId
    private UUID tenantId;

    private UUID workOrderId;

    private String objectKey;

    /** As the uploader named it; shown to people, never used to build the storage key. */
    private String fileName;

    private String contentType;

    private long declaredSize;

    /** Size of the stored object, known once confirmed. */
    private Long size;

    @Enumerated(EnumType.STRING)
    private AttachmentStatus status;

    /** Keycloak user id of the uploader. */
    private UUID uploadedBy;

    @Column(updatable = false)
    private Instant createdAt;

    private Instant confirmedAt;

    // Also tells Spring Data a new attachment (version still null) apart from a saved one, although the id
    // is set up front: the storage key needs it before the row exists.
    @Version
    private Long version;

    protected Attachment() {} // for JPA

    /**
     * The server picks the key: scoped by tenant and work order, unguessable, and free of user input (a file
     * name could contain "../", personal data, or collide with another file).
     */
    static Attachment pending(
            UUID tenantId, UUID workOrderId, String fileName, FileType type, long declaredSize, UUID uploadedBy) {
        var attachment = new Attachment();
        attachment.id = UUID.randomUUID();
        attachment.tenantId = tenantId;
        attachment.workOrderId = workOrderId;
        attachment.objectKey =
                "tenants/%s/work-orders/%s/%s.%s".formatted(tenantId, workOrderId, attachment.id, type.extension);
        attachment.fileName = fileName.strip();
        attachment.contentType = type.contentType;
        attachment.declaredSize = declaredSize;
        attachment.status = AttachmentStatus.PENDING;
        attachment.uploadedBy = uploadedBy;
        attachment.createdAt = Timestamps.now();
        return attachment;
    }

    void confirm(long storedSize) {
        this.size = storedSize;
        this.status = AttachmentStatus.CONFIRMED;
        this.confirmedAt = Timestamps.now();
    }

    UUID id() {
        return id;
    }

    UUID workOrderId() {
        return workOrderId;
    }

    String objectKey() {
        return objectKey;
    }

    String fileName() {
        return fileName;
    }

    String contentType() {
        return contentType;
    }

    long declaredSize() {
        return declaredSize;
    }

    Long size() {
        return size;
    }

    AttachmentStatus status() {
        return status;
    }

    Instant createdAt() {
        return createdAt;
    }
}
