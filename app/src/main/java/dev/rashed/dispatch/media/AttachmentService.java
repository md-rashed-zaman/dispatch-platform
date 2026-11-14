package dev.rashed.dispatch.media;

import dev.rashed.dispatch.identity.CurrentUser;
import dev.rashed.dispatch.shared.DependencyUnavailableException;
import dev.rashed.dispatch.shared.NotFoundException;
import dev.rashed.dispatch.tenancy.TenantContext;
import dev.rashed.dispatch.workorder.WorkOrderAccess;
import dev.rashed.dispatch.workorder.WorkOrderRef;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * Uploads in three steps (T15): the client asks for permission and gets a presigned PUT URL, uploads the
 * bytes straight to storage, then confirms, and the server checks what actually arrived. Company staff and
 * the assigned technician may attach files; anyone else gets 404.
 *
 * <p>Storage calls run outside database transactions, so a slow storage response never holds a connection.
 */
@Service
@PreAuthorize("hasAnyRole('CLIENT_ADMIN', 'DISPATCHER', 'TECHNICIAN')")
public class AttachmentService {

    static final long MAX_SIZE_BYTES = 10L * 1024 * 1024;
    static final int MAX_PER_WORK_ORDER = 30;
    /** Minutes, not days: a leaked link stops working soon. */
    static final Duration URL_LIFETIME = Duration.ofMinutes(5);

    private final WorkOrderAccess access;
    private final AttachmentRepository attachments;
    private final S3Client s3;
    private final S3Presigner presigner;
    private final StorageProperties storage;
    private final TransactionTemplate transaction;

    AttachmentService(
            WorkOrderAccess access,
            AttachmentRepository attachments,
            S3Client s3,
            S3Presigner presigner,
            StorageProperties storage,
            PlatformTransactionManager transactionManager) {
        this.access = access;
        this.attachments = attachments;
        this.s3 = s3;
        this.presigner = presigner;
        this.storage = storage;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public UploadTicket requestUpload(UUID workOrderId, UploadRequest request) {
        var workOrder = openWorkOrder(workOrderId);
        var type = FileType.of(request.contentType()).orElseThrow(); // validated by UploadRequest
        var uploader = CurrentUser.current().orElseThrow().id();
        var attachment = asTenant(workOrder, () -> {
            if (attachments.countByWorkOrderId(workOrderId) >= MAX_PER_WORK_ORDER) {
                throw new AttachmentRuleException(
                        "too-many-attachments", "A work order holds at most " + MAX_PER_WORK_ORDER + " files.");
            }
            return attachments.save(Attachment.pending(
                    workOrder.tenantId(), workOrderId, request.fileName(), type, request.size(), uploader));
        });
        // Content type and length are part of the signature: an upload with another type or size doesn't
        // match it, and storage refuses it.
        var presigned = presigner.presignPutObject(p -> p.signatureDuration(URL_LIFETIME)
                .putObjectRequest(o -> o.bucket(storage.bucket())
                        .key(attachment.objectKey())
                        .contentType(type.contentType)
                        .contentLength(request.size())));
        Map<String, String> headers = presigned.signedHeaders().entrySet().stream()
                .filter(h -> !h.getKey().equalsIgnoreCase("host")) // the HTTP client sets it from the URL
                .collect(Collectors.toMap(Map.Entry::getKey, h -> String.join(",", h.getValue())));
        return new UploadTicket(attachment.id(), "PUT", presigned.url().toString(), headers, presigned.expiration());
    }

    /**
     * Checks the stored file against the claim: it must exist, have the declared size and content type, and
     * start with that type's signature bytes. A file that fails is deleted. Confirming twice is harmless.
     */
    public AttachmentView confirm(UUID workOrderId, UUID attachmentId) {
        var workOrder = openWorkOrder(workOrderId);
        var attachment = asTenant(
                workOrder,
                () -> attachments
                        .findByIdAndWorkOrderId(attachmentId, workOrderId)
                        .orElseThrow(() -> new NotFoundException("Attachment", attachmentId)));
        if (attachment.status() == AttachmentStatus.CONFIRMED) {
            return view(attachment);
        }
        HeadObjectResponse stored;
        try {
            stored = reachStorage(
                    () -> s3.headObject(h -> h.bucket(storage.bucket()).key(attachment.objectKey())));
        } catch (NoSuchKeyException e) {
            throw new AttachmentRuleException(
                    "upload-missing", "Nothing has been uploaded yet. Upload the file, then confirm.");
        }
        var problem = problemWith(attachment, stored);
        if (problem != null) {
            reachStorage(() -> s3.deleteObject(d -> d.bucket(storage.bucket()).key(attachment.objectKey())));
            asTenant(workOrder, () -> {
                attachments.deleteById(attachmentId);
                return null;
            });
            throw new AttachmentRuleException("upload-rejected", problem + " The file was deleted.");
        }
        return view(asTenant(workOrder, () -> {
            var pending = attachments.findById(attachmentId).orElseThrow();
            pending.confirm(stored.contentLength());
            return attachments.save(pending);
        }));
    }

    /** Confirmed files only, oldest first. At most {@value #MAX_PER_WORK_ORDER}, so no paging. */
    public List<AttachmentView> list(UUID workOrderId) {
        var workOrder = access.forCaller(workOrderId).orElseThrow(() -> notFound(workOrderId));
        return asTenant(
                        workOrder,
                        () -> attachments.findByWorkOrderIdAndStatusOrderByCreatedAt(
                                workOrderId, AttachmentStatus.CONFIRMED))
                .stream()
                .map(this::view)
                .toList();
    }

    private String problemWith(Attachment attachment, HeadObjectResponse stored) {
        if (stored.contentLength() > MAX_SIZE_BYTES) {
            return "The file is larger than " + MAX_SIZE_BYTES / (1024 * 1024) + " MB.";
        }
        if (stored.contentLength() != attachment.declaredSize()) {
            return "The file is " + stored.contentLength() + " bytes, not the " + attachment.declaredSize()
                    + " declared.";
        }
        if (!attachment.contentType().equals(stored.contentType())) {
            return "The file was stored as " + stored.contentType() + ", not " + attachment.contentType() + ".";
        }
        // shortcut: checks the signature bytes only. Decoding images and stripping EXIF (GPS location) comes
        // with photo processing for the AI completion audit (step 3.5); scanning for malware before others
        // download files, with real users.
        var firstBytes = reachStorage(() -> s3.getObjectAsBytes(g -> g.bucket(storage.bucket())
                        .key(attachment.objectKey())
                        .range("bytes=0-" + (FileType.SIGNATURE_LENGTH - 1))))
                .asByteArray();
        var type = FileType.of(attachment.contentType()).orElseThrow();
        return type.matches(firstBytes) ? null : "The file's content isn't a real " + type.extension + " file.";
    }

    /**
     * Storage not answering at all (down, unreachable, timing out) becomes a 503 the client can retry. Answers
     * from storage, like "no such key", pass through unchanged. Presigning needs no call: it's local signing.
     */
    private static <T> T reachStorage(Supplier<T> call) {
        try {
            return call.get();
        } catch (SdkClientException e) {
            throw new DependencyUnavailableException("file storage", e);
        }
    }

    private WorkOrderRef openWorkOrder(UUID workOrderId) {
        var workOrder = access.forCaller(workOrderId).orElseThrow(() -> notFound(workOrderId));
        if (workOrder.isClosed()) {
            throw new AttachmentRuleException(
                    "work-order-closed", "The work order is " + workOrder.status() + "; its files can't change.");
        }
        return workOrder;
    }

    private AttachmentView view(Attachment attachment) {
        var presigned = presigner.presignGetObject(p -> p.signatureDuration(URL_LIFETIME)
                .getObjectRequest(g -> g.bucket(storage.bucket()).key(attachment.objectKey())));
        return new AttachmentView(
                attachment.id(),
                attachment.fileName(),
                attachment.contentType(),
                attachment.size(),
                attachment.createdAt(),
                presigned.url().toString(),
                presigned.expiration());
    }

    // Company staff already work for this tenant; technicians reach it this way after WorkOrderAccess has
    // checked they're assigned (ADR 0005).
    private <T> T asTenant(WorkOrderRef workOrder, Supplier<T> work) {
        return TenantContext.callAs(workOrder.tenantId(), () -> transaction.execute(status -> work.get()));
    }

    private static NotFoundException notFound(UUID workOrderId) {
        return new NotFoundException("Work order", workOrderId);
    }
}
