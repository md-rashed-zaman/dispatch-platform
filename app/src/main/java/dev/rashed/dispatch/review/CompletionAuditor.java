package dev.rashed.dispatch.review;

import dev.rashed.dispatch.ai.CompletionCheck;
import dev.rashed.dispatch.media.WorkOrderPhotos;
import dev.rashed.dispatch.outbox.Topics;
import dev.rashed.dispatch.tenancy.TenantContext;
import dev.rashed.dispatch.workorder.WorkOrderCompletion;
import dev.rashed.dispatch.workorder.WorkOrderStatus;
import dev.rashed.dispatch.workorder.WorkOrderStatusChangedV1;
import java.util.Optional;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Audits every submitted job (step 3.5): reads the checklist, notes and photos, asks the AI check, applies
 * {@link AuditPolicy}, and either approves as the system or leaves the audit in the review queue. Off the request
 * path: the technician's submit returns at once, and a slow or failing model only delays the audit.
 *
 * <p>Idempotent by the SUBMITTED event's id (unique in the table). Also closes an open audit when the work order
 * is approved, rejected or cancelled some other way, recording what was decided: that's eval data too.
 */
@Component
class CompletionAuditor {

    private static final Logger log = LoggerFactory.getLogger(CompletionAuditor.class);

    static final String CONSUMER = "review-auditor";

    private final WorkOrderCompletion workOrders;
    private final WorkOrderPhotos photos;
    private final CompletionCheck check;
    private final CompletionAudits audits;
    private final ReviewProperties review;
    private final JsonMapper json;

    CompletionAuditor(
            WorkOrderCompletion workOrders,
            WorkOrderPhotos photos,
            CompletionCheck check,
            CompletionAudits audits,
            ReviewProperties review,
            JsonMapper json) {
        this.workOrders = workOrders;
        this.photos = photos;
        this.check = check;
        this.audits = audits;
        this.review = review;
        this.json = json;
    }

    @KafkaListener(
            id = CONSUMER,
            groupId = CONSUMER,
            topics = Topics.WORK_ORDER_EVENTS,
            containerFactory = "auditorListenerFactory",
            autoStartup = "${dispatch.review.auditor-enabled:${dispatch.notification.consumer-enabled:true}}")
    void on(ConsumerRecord<String, String> record) {
        var envelope = json.readTree(record.value());
        if (!WorkOrderStatusChangedV1.TYPE.equals(envelope.path("type").asText())) {
            return;
        }
        var eventId = UUID.fromString(envelope.path("eventId").asText());
        var tenant = UUID.fromString(envelope.path("tenantId").asText());
        var change = json.treeToValue(envelope.path("payload"), WorkOrderStatusChangedV1.class);
        TenantContext.runAs(tenant, () -> {
            if (change.to() == WorkOrderStatus.SUBMITTED) {
                audit(eventId, tenant, change.workOrderId());
            } else if (change.from() == WorkOrderStatus.SUBMITTED) {
                // Approved, rejected (back to in progress) or cancelled outside the queue.
                audits.closeOpen(change.workOrderId(), change.to().name());
            }
        });
    }

    void audit(UUID eventId, UUID tenant, UUID workOrderId) {
        if (audits.exists(eventId)) {
            return; // a redelivered event: don't pay for the model call again
        }
        var snapshot = workOrders.snapshot(workOrderId).orElse(null);
        if (snapshot == null || snapshot.status() != WorkOrderStatus.SUBMITTED) {
            return; // decided (or withdrawn) before this event was read: nothing to audit
        }
        var proof = photos.photos(workOrderId, review.maxPhotos());
        Optional<CompletionCheck.Assessment> assessment = proof.isEmpty()
                ? Optional.empty() // nothing to look at: don't spend a model call
                : check.check(new CompletionCheck.Input(
                        snapshot.title(),
                        snapshot.description(),
                        snapshot.notes(),
                        snapshot.checklist().stream()
                                .map(i -> new CompletionCheck.ChecklistItem(i.position(), i.description()))
                                .toList(),
                        proof.stream()
                                .map(p -> new CompletionCheck.Photo(p.contentType(), p.bytes()))
                                .toList()));
        var decision = AuditPolicy.decide(assessment, proof.size(), review.threshold(), review.autoApprove());

        var auditId = UUID.randomUUID();
        if (!audits.insert(auditId, tenant, workOrderId, eventId, assessment, proof.size(), decision)) {
            return; // this submission was audited already
        }
        if (decision.recommendation() == AuditPolicy.Recommendation.APPROVE) {
            if (workOrders.approveAsSystem(workOrderId)) {
                audits.decide(auditId, CompletionAudits.State.AUTO_APPROVED, "system", null);
            } else {
                audits.closeOpen(workOrderId, "decided before the AI check finished");
            }
        }
        log.info("Work order {} audited: {} ({})", workOrderId, decision.recommendation(), decision.reason());
    }
}
