package dev.rashed.dispatch.tenancy;

import java.util.UUID;

/** API view of a company. Entities are never serialized directly (no lazy-loading or field leaks). */
public record CompanySummary(UUID id, String name, String slug) {

    static CompanySummary of(Company company) {
        return new CompanySummary(company.id(), company.name(), company.slug());
    }
}
