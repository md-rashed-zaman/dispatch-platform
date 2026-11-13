package dev.rashed.dispatch.tenancy;

import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class CompanyService {

    private final CompanyRepository companies;

    CompanyService(CompanyRepository companies) {
        this.companies = companies;
    }

    // Checked here, not in the controller: every caller (REST, scheduled job, future MCP tool) goes
    // through this method, so the rule can't be skipped by adding a new entry point.
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @Transactional(readOnly = true)
    public List<CompanySummary> listAll() {
        return companies.findAll(Sort.by("name")).stream()
                .map(CompanySummary::of)
                .toList();
    }
}
