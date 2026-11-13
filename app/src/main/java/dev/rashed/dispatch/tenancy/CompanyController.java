package dev.rashed.dispatch.tenancy;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Companies", description = "Tenant companies")
@RestController
@RequestMapping("/api/companies")
class CompanyController {

    private final CompanyService companyService;

    CompanyController(CompanyService companyService) {
        this.companyService = companyService;
    }

    @Operation(summary = "List all tenant companies (platform admins only)")
    @GetMapping
    List<CompanySummary> list() {
        return companyService.listAll();
    }
}
