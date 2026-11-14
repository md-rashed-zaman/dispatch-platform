package dev.rashed.dispatch.media;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

// Tenant filtering is automatic (@TenantId and row-level security).
interface AttachmentRepository extends JpaRepository<Attachment, UUID> {

    Optional<Attachment> findByIdAndWorkOrderId(UUID id, UUID workOrderId);

    List<Attachment> findByWorkOrderIdAndStatusOrderByCreatedAt(UUID workOrderId, AttachmentStatus status);

    long countByWorkOrderId(UUID workOrderId);

    List<Attachment> findByStatusAndCreatedAtBefore(AttachmentStatus status, Instant cutoff, Limit limit);
}
