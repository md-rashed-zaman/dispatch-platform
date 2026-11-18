package dev.rashed.dispatch.media;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * A work order's confirmed photos as bytes, for the completion audit (step 3.5): a system caller, so no role
 * check. Call as the work order's tenant: the attachment table has row-level security.
 */
@Service
public class WorkOrderPhotos {

    public record Photo(UUID attachmentId, String fileName, String contentType, byte[] bytes) {}

    private final AttachmentRepository attachments;
    private final S3Client s3;
    private final StorageProperties storage;

    WorkOrderPhotos(AttachmentRepository attachments, S3Client s3, StorageProperties storage) {
        this.attachments = attachments;
        this.s3 = s3;
        this.storage = storage;
    }

    /** JPEG and PNG only (PDFs aren't photos), oldest first, at most {@code limit}. */
    @Transactional(readOnly = true)
    public List<Photo> photos(UUID workOrderId, int limit) {
        return attachments.findByWorkOrderIdAndStatusOrderByCreatedAt(workOrderId, AttachmentStatus.CONFIRMED).stream()
                .filter(a -> a.contentType().startsWith("image/"))
                .limit(limit)
                .map(a -> new Photo(
                        a.id(),
                        a.fileName(),
                        a.contentType(),
                        s3.getObjectAsBytes(g -> g.bucket(storage.bucket()).key(a.objectKey()))
                                .asByteArray()))
                .toList();
    }
}
