package dev.rashed.dispatch.tenancy;

import dev.rashed.dispatch.shared.Timestamps;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/** A tenant: a client company that posts work orders. Aggregate root. */
@Entity
@Table(name = "company")
public class Company {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    private String name;

    /** URL-safe unique name, e.g. {@code city-bank}. */
    private String slug;

    @Column(updatable = false)
    private Instant createdAt;

    protected Company() {} // for JPA

    private Company(String name, String slug) {
        if (name == null || name.isBlank() || name.length() > 200) {
            throw new IllegalArgumentException("Company name must be 1 to 200 characters");
        }
        if (slug == null || !slug.matches("[a-z0-9]+(-[a-z0-9]+)*")) {
            throw new IllegalArgumentException("Invalid slug: " + slug);
        }
        this.name = name.strip();
        this.createdAt = Timestamps.now();
        this.slug = slug;
    }

    public static Company create(String name, String slug) {
        return new Company(name, slug);
    }

    public UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String slug() {
        return slug;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
