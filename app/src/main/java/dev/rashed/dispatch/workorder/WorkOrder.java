package dev.rashed.dispatch.workorder;

import dev.rashed.dispatch.shared.GeoPoint;
import dev.rashed.dispatch.shared.Money;
import dev.rashed.dispatch.shared.Skill;
import dev.rashed.dispatch.shared.TimeWindow;
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
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.TenantId;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/**
 * A request for on-site work. Aggregate root: owns its checklist items, and refers to its tenant and
 * technician by id only (they are other aggregates, loaded and locked separately).
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

    // Unidirectional: items never navigate back to their work order, so there's no back-reference to keep in sync.
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "work_order_id", nullable = false, updatable = false)
    @OrderBy("position")
    private List<ChecklistItem> checklist = new ArrayList<>();

    @Version
    private Long version;

    @CreationTimestamp
    private Instant createdAt;

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
        this.title = requireText(title, "Title", 200);
        this.description = requireText(description, "Description", 5000);
        this.siteAddress = requireText(siteAddress, "Site address", 500);
        this.site = Objects.requireNonNull(site, "site");
        this.requiredSkills = Skill.codes(requiredSkills);
        this.timeWindow = Objects.requireNonNull(timeWindow, "timeWindow");
        this.budget = Objects.requireNonNull(budget, "budget");
        if (!budget.isPositive()) {
            throw new IllegalArgumentException("Budget must be greater than zero");
        }
        this.status = WorkOrderStatus.DRAFT;
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

    /** The checklist is what the technician is paid against, so it can only change while drafting. */
    public ChecklistItem addChecklistItem(String description) {
        if (status != WorkOrderStatus.DRAFT) {
            throw new IllegalStateException("Checklist can only change while the work order is a draft");
        }
        if (checklist.size() >= MAX_CHECKLIST_ITEMS) {
            throw new IllegalStateException(
                    "A work order can have at most " + MAX_CHECKLIST_ITEMS + " checklist items");
        }
        var item = new ChecklistItem(checklist.size(), description);
        checklist.add(item);
        return item;
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
