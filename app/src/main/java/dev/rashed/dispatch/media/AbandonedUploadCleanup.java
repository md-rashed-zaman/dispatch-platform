package dev.rashed.dispatch.media;

import dev.rashed.dispatch.shared.Timestamps;
import dev.rashed.dispatch.tenancy.TenantContext;
import dev.rashed.dispatch.tenancy.TenantDirectory;
import java.time.Duration;
import java.time.Instant;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Deletes uploads that were started but never confirmed (the app crashed, the user gave up), and their
 * files. One instance runs it at a time; deleting what's already gone does nothing, so a second run is harmless.
 */
@Component
class AbandonedUploadCleanup {

    private static final Logger log = LoggerFactory.getLogger(AbandonedUploadCleanup.class);

    /** Far longer than an upload URL lives, so nothing still uploading is touched. */
    static final Duration ABANDONED_AFTER = Duration.ofHours(1);

    private static final int BATCH = 100;

    private final TenantDirectory tenants;
    private final AttachmentRepository attachments;
    private final S3Client s3;
    private final StorageProperties storage;
    private final TransactionTemplate transaction;

    AbandonedUploadCleanup(
            TenantDirectory tenants,
            AttachmentRepository attachments,
            S3Client s3,
            StorageProperties storage,
            PlatformTransactionManager transactionManager) {
        this.tenants = tenants;
        this.attachments = attachments;
        this.s3 = s3;
        this.storage = storage;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    // One instance at a time (T22). Running twice would still be harmless: deleting what's gone does nothing.
    @Scheduled(fixedDelayString = "PT10M", initialDelayString = "PT1M")
    @SchedulerLock(name = "abandoned-upload-cleanup", lockAtMostFor = "PT9M")
    void run() {
        var removed = removeOlderThan(Timestamps.now().minus(ABANDONED_AFTER));
        if (removed > 0) {
            log.info("Removed {} abandoned uploads", removed);
        }
    }

    /** One tenant at a time: row-level security shows nothing to a job that names no tenant. */
    int removeOlderThan(Instant cutoff) {
        var removed = 0;
        for (var tenantId : tenants.allTenantIds()) {
            removed += TenantContext.callAs(tenantId, () -> removeForCurrentTenant(cutoff));
        }
        return removed;
    }

    private int removeForCurrentTenant(Instant cutoff) {
        var removed = 0;
        while (true) {
            var batch = transaction.execute(status ->
                    attachments.findByStatusAndCreatedAtBefore(AttachmentStatus.PENDING, cutoff, Limit.of(BATCH)));
            if (batch == null || batch.isEmpty()) {
                return removed;
            }
            for (var attachment : batch) {
                // The file first: if this fails, the row stays and the next run tries again. The other order
                // could leave a file that nothing points to.
                s3.deleteObject(d -> d.bucket(storage.bucket()).key(attachment.objectKey()));
                transaction.executeWithoutResult(status -> attachments.deleteById(attachment.id()));
                removed++;
            }
        }
    }
}
