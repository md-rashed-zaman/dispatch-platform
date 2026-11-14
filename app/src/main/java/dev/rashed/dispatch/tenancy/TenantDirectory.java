package dev.rashed.dispatch.tenancy;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tenants as seen by background jobs and other modules: every tenant id, for jobs that must visit each tenant in turn (row-level security shows a job
 * nothing until it names a tenant with {@link TenantContext#callAs}). Not for request handling.
 */
@Service
public class TenantDirectory {

    private final CompanyRepository companies;

    TenantDirectory(CompanyRepository companies) {
        this.companies = companies;
    }

    @Transactional(readOnly = true)
    public List<UUID> allTenantIds() {
        return companies.findAll().stream().map(Company::id).toList();
    }

    /** Who receives the company's notifications, if anyone. */
    public record NotificationContact(String companyName, String email) {}

    @Transactional(readOnly = true)
    public Optional<NotificationContact> notificationContact(UUID tenantId) {
        return companies
                .findById(tenantId)
                .flatMap(company ->
                        company.notificationEmail().map(email -> new NotificationContact(company.name(), email)));
    }
}
