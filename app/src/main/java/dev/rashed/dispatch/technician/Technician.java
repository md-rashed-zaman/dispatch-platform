package dev.rashed.dispatch.technician;

import dev.rashed.dispatch.shared.GeoPoint;
import dev.rashed.dispatch.shared.Skill;
import dev.rashed.dispatch.shared.Timestamps;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/** A freelance technician who can take work orders from any tenant. Aggregate root. */
@Entity
@Table(name = "technician")
public class Technician {

    static final int MAX_SERVICE_RADIUS_KM = 100;

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    private String fullName;

    /** Bangladeshi mobile number in E.164 form, e.g. {@code +8801712345678}. */
    private String phone;

    @JdbcTypeCode(SqlTypes.ARRAY)
    private Set<String> skills;

    @Embedded
    @AttributeOverride(name = "latitude", column = @Column(name = "home_latitude"))
    @AttributeOverride(name = "longitude", column = @Column(name = "home_longitude"))
    private GeoPoint home;

    private int serviceRadiusKm;

    /** Average rating from 1.00 to 5.00; null until the first rated job. */
    private BigDecimal rating;

    @Enumerated(EnumType.STRING)
    private TechnicianStatus status;

    @Version
    private Long version;

    @Column(updatable = false)
    private Instant createdAt;

    protected Technician() {} // for JPA

    private Technician(String fullName, String phone, Collection<String> skills, GeoPoint home, int serviceRadiusKm) {
        if (fullName == null || fullName.isBlank() || fullName.length() > 200) {
            throw new IllegalArgumentException("Full name must be 1 to 200 characters");
        }
        if (phone == null || !phone.matches("\\+8801[3-9]\\d{8}")) {
            throw new IllegalArgumentException("Phone must be a Bangladeshi mobile number like +8801712345678");
        }
        if (home == null) {
            throw new IllegalArgumentException("Home location is required");
        }
        if (serviceRadiusKm < 1 || serviceRadiusKm > MAX_SERVICE_RADIUS_KM) {
            throw new IllegalArgumentException("Service radius must be 1 to " + MAX_SERVICE_RADIUS_KM + " km");
        }
        this.fullName = fullName.strip();
        this.phone = phone;
        this.skills = Skill.codes(skills);
        this.home = home;
        this.serviceRadiusKm = serviceRadiusKm;
        this.status = TechnicianStatus.PENDING_VERIFICATION;
        this.createdAt = Timestamps.now();
    }

    public static Technician register(
            String fullName, String phone, Collection<String> skills, GeoPoint home, int serviceRadiusKm) {
        return new Technician(fullName, phone, skills, home, serviceRadiusKm);
    }

    public UUID id() {
        return id;
    }

    public String fullName() {
        return fullName;
    }

    public String phone() {
        return phone;
    }

    public Set<Skill> skills() {
        return skills.stream().map(Skill::new).collect(Collectors.toUnmodifiableSet());
    }

    public GeoPoint home() {
        return home;
    }

    public int serviceRadiusKm() {
        return serviceRadiusKm;
    }

    public BigDecimal rating() {
        return rating;
    }

    public TechnicianStatus status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
