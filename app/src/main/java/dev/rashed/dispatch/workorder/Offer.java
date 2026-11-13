package dev.rashed.dispatch.workorder;

import dev.rashed.dispatch.shared.Timestamps;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/**
 * An invitation for one technician to take one work order, until it expires. Its own aggregate: many
 * technicians respond to offers for the same work order at once, and each offer changes on its own.
 */
@Entity
@Table(name = "offer")
public class Offer {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    private UUID workOrderId;

    private UUID technicianId;

    @Enumerated(EnumType.STRING)
    private OfferStatus status;

    private Instant expiresAt;

    private Instant respondedAt;

    @Column(updatable = false)
    private Instant createdAt;

    protected Offer() {} // for JPA

    private Offer(UUID workOrderId, UUID technicianId, Instant expiresAt) {
        this.workOrderId = Objects.requireNonNull(workOrderId, "workOrderId");
        this.technicianId = Objects.requireNonNull(technicianId, "technicianId");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        this.status = OfferStatus.OFFERED;
        this.createdAt = Timestamps.now();
    }

    static Offer create(UUID workOrderId, UUID technicianId, Instant expiresAt) {
        return new Offer(workOrderId, technicianId, expiresAt);
    }

    public UUID id() {
        return id;
    }

    public UUID workOrderId() {
        return workOrderId;
    }

    public UUID technicianId() {
        return technicianId;
    }

    public OfferStatus status() {
        return status;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public Instant respondedAt() {
        return respondedAt;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
