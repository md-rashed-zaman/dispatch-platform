package dev.rashed.dispatch.workorder;

import dev.rashed.dispatch.shared.BusinessRuleException;
import dev.rashed.dispatch.shared.GeoPoint;
import dev.rashed.dispatch.shared.Money;
import dev.rashed.dispatch.shared.Skill;
import dev.rashed.dispatch.shared.TimeWindow;
import dev.rashed.dispatch.shared.Timestamps;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.TenantId;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.AfterDomainEventPublication;
import org.springframework.data.domain.DomainEvents;

/**
 * A request for on-site work. Aggregate root: owns its checklist items, and refers to its tenant and
 * technician by id only (they are other aggregates, loaded and locked separately).
 *
 * <p>The status changes only through the transition methods ({@link #publish()}, {@link #approve()}, ...),
 * each checked against {@link WorkOrderStatus}'s table. There is no status setter.
 */
@Entity
@Table(name = "work_order")
public class WorkOrder {

    static final int MAX_CHECKLIST_ITEMS = 50;

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    /**
     * The owning company. Hibernate filters every query by the current tenant and refuses to save a work
     * order for any other tenant (see TenantContext).
     */
    @TenantId
    private UUID tenantId;

    private String title;

    private String description;

    @Enumerated(EnumType.STRING)
    private WorkOrderStatus status;

    private String siteAddress;

    @Embedded
    @AttributeOverride(name = "latitude", column = @Column(name = "site_latitude"))
    @AttributeOverride(name = "longitude", column = @Column(name = "site_longitude"))
    private GeoPoint site;

    @JdbcTypeCode(SqlTypes.ARRAY)
    private Set<String> requiredSkills;

    @Embedded
    @AttributeOverride(name = "startsAt", column = @Column(name = "window_starts_at"))
    @AttributeOverride(name = "endsAt", column = @Column(name = "window_ends_at"))
    private TimeWindow timeWindow;

    @Embedded
    @AttributeOverride(name = "minor", column = @Column(name = "budget_minor"))
    @AttributeOverride(name = "currency", column = @Column(name = "budget_currency"))
    private Money budget;

    /** Set on assignment. An id, not a Technician object: technicians are a separate aggregate. */
    private UUID technicianId;

    /** Why the submitted work was sent back. Cleared when the technician submits again. */
    private String rejectionReason;

    // Unidirectional: items never navigate back to their work order, so there's no back-reference to keep in sync.
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "work_order_id", nullable = false, updatable = false)
    @OrderBy("position")
    private List<ChecklistItem> checklist = new ArrayList<>();

    @Version
    private Long version;

    @Column(updatable = false)
    private Instant createdAt;

    // Published by Spring Data when the repository saves this work order, then cleared. Listeners in other
    // modules run after the transaction commits (T16), so they never react to a change that was rolled back.
    @Transient
    private final List<Object> events = new ArrayList<>();

    protected WorkOrder() {} // for JPA

    private WorkOrder(
            UUID tenantId,
            String title,
            String description,
            String siteAddress,
            GeoPoint site,
            Collection<String> requiredSkills,
            TimeWindow timeWindow,
            Money budget) {
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.status = WorkOrderStatus.DRAFT;
        this.createdAt = Timestamps.now();
        revise(new WorkOrderChanges(
                Objects.requireNonNull(title, "title"),
                Objects.requireNonNull(description, "description"),
                Objects.requireNonNull(siteAddress, "siteAddress"),
                Objects.requireNonNull(site, "site"),
                Set.copyOf(Objects.requireNonNull(requiredSkills, "requiredSkills")),
                Objects.requireNonNull(timeWindow, "timeWindow"),
                Objects.requireNonNull(budget, "budget")));
    }

    public static WorkOrder draft(
            UUID tenantId,
            String title,
            String description,
            String siteAddress,
            GeoPoint site,
            Collection<String> requiredSkills,
            TimeWindow timeWindow,
            Money budget) {
        return new WorkOrder(tenantId, title, description, siteAddress, site, requiredSkills, timeWindow, budget);
    }

    /**
     * Edits the fields given in {@code changes}, leaving the others. Only drafts: once published, technicians
     * may already be deciding based on what they saw.
     */
    public void revise(WorkOrderChanges changes) {
        requireDraft("edited");
        if (changes.title() != null) {
            this.title = requireText(changes.title(), "Title", 200);
        }
        if (changes.description() != null) {
            this.description = requireText(changes.description(), "Description", 5000);
        }
        if (changes.siteAddress() != null) {
            this.siteAddress = requireText(changes.siteAddress(), "Site address", 500);
        }
        if (changes.site() != null) {
            this.site = changes.site();
        }
        if (changes.requiredSkills() != null) {
            this.requiredSkills = Skill.codes(changes.requiredSkills());
        }
        if (changes.timeWindow() != null) {
            this.timeWindow = changes.timeWindow();
        }
        if (changes.budget() != null) {
            if (!changes.budget().isPositive()) {
                throw new IllegalArgumentException("Budget must be greater than zero");
            }
            this.budget = changes.budget();
        }
    }

    /** The checklist is what the technician is paid against, so it can only change while drafting. */
    public ChecklistItem addChecklistItem(String description) {
        requireDraft("given checklist items");
        if (checklist.size() >= MAX_CHECKLIST_ITEMS) {
            throw new BusinessRuleException(
                    "A work order can have at most " + MAX_CHECKLIST_ITEMS + " checklist items");
        }
        var item = new ChecklistItem(checklist.size(), description);
        checklist.add(item);
        return item;
    }

    // ---- Lifecycle: one method per command in the Transition table, which each of them checks. ----

    /** Ready for technicians: matching starts looking for candidates. */
    public void publish() {
        requireAllowed(Transition.PUBLISH);
        if (checklist.isEmpty()) {
            throw new BusinessRuleException("Add at least one checklist item before publishing");
        }
        apply(Transition.PUBLISH);
        events.add(new WorkOrderPublished(
                id,
                tenantId,
                site,
                requiredSkills().stream().map(Skill::code).sorted().toList(),
                Timestamps.now()));
    }

    /** Offers were sent to the nearest matching technicians (on publish, see WorkOrderService). */
    public void route() {
        apply(Transition.ROUTE);
    }

    /** Nobody accepted in time: back to PUBLISHED so matching can try other technicians. */
    public void offersExpired() {
        apply(Transition.OFFERS_EXPIRED);
    }

    /**
     * The same transition in memory. Accepting an offer doesn't use it: when many technicians accept at
     * once, {@link WorkOrderRepository#assignIfStillRouted} applies it in one atomic UPDATE (ADR 0005).
     */
    public void assign(UUID technicianId) {
        Objects.requireNonNull(technicianId, "technicianId");
        requireAllowed(Transition.ASSIGN);
        this.technicianId = technicianId;
        apply(Transition.ASSIGN);
        events.add(new WorkOrderAssigned(id, tenantId, technicianId, title, Timestamps.now()));
    }

    public void start() {
        apply(Transition.START);
    }

    public void submit() {
        requireAllowed(Transition.SUBMIT);
        this.rejectionReason = null;
        apply(Transition.SUBMIT);
    }

    /** The client accepts the work; payment can be released. */
    public void approve() {
        apply(Transition.APPROVE);
    }

    /** Sends the work back to the technician, who sees {@code reason}, fixes it and submits again. */
    public void reject(String reason) {
        requireAllowed(Transition.REJECT);
        this.rejectionReason = requireText(reason, "Rejection reason", 1000);
        apply(Transition.REJECT);
    }

    /** The technician's payout was released (payment, Phase 4). */
    public void markPaid() {
        apply(Transition.MARK_PAID);
    }

    public void cancel() {
        apply(Transition.CANCEL);
    }

    private void requireAllowed(Transition transition) {
        if (!transition.from.contains(status)) {
            throw new InvalidTransitionException(transition.verb, status);
        }
    }

    private void requireDraft(String what) {
        if (status != WorkOrderStatus.DRAFT) {
            throw new BusinessRuleException("Only a draft can be " + what + "; this work order is " + status);
        }
    }

    /** Checks the table (again, for methods that checked it before their guards) and moves. */
    private void apply(Transition transition) {
        requireAllowed(transition);
        events.add(new WorkOrderStatusChanged(id, tenantId, status, transition.to, technicianId, Instant.now()));
        this.status = transition.to;
    }

    @DomainEvents
    List<Object> pendingEvents() {
        return List.copyOf(events);
    }

    @AfterDomainEventPublication
    void clearEvents() {
        events.clear();
    }

    public UUID id() {
        return id;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public String title() {
        return title;
    }

    public String description() {
        return description;
    }

    public WorkOrderStatus status() {
        return status;
    }

    public String siteAddress() {
        return siteAddress;
    }

    public GeoPoint site() {
        return site;
    }

    public Set<Skill> requiredSkills() {
        return requiredSkills.stream().map(Skill::new).collect(Collectors.toUnmodifiableSet());
    }

    public TimeWindow timeWindow() {
        return timeWindow;
    }

    public Money budget() {
        return budget;
    }

    public UUID technicianId() {
        return technicianId;
    }

    public String rejectionReason() {
        return rejectionReason;
    }

    /** Increases with every saved change; the API sends it as the ETag (optimistic locking). */
    public long version() {
        return version == null ? 0 : version;
    }

    /** Read-only view: callers can't add or remove items behind the aggregate's back. */
    public List<ChecklistItem> checklist() {
        return List.copyOf(checklist);
    }

    public Instant createdAt() {
        return createdAt;
    }

    private static String requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(field + " must be 1 to " + maxLength + " characters");
        }
        return value.strip();
    }
}
