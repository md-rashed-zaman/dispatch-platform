package dev.rashed.dispatch.workorder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.rashed.dispatch.TestTechnicians;
import dev.rashed.dispatch.TestTokens;
import dev.rashed.dispatch.TestcontainersConfiguration;
import dev.rashed.dispatch.tenancy.TenantContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AssignedWorkTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate transaction;

    @Autowired
    WorkOrderRepository workOrders;

    final UUID tenant = UUID.randomUUID();
    UUID technician;
    UUID workOrder;

    @BeforeEach
    void setUp() {
        jdbc.update("insert into company (id, name, slug) values (?, 'Company', ?)", tenant, "company-" + tenant);
        technician = TestTechnicians.verified(jdbc, 23.79, 90.41, "networking");
        workOrder = TenantContext.callAs(
                tenant,
                () -> transaction.execute(s -> workOrders
                        .save(WorkOrders.inStatus(tenant, technician, WorkOrderStatus.ASSIGNED))
                        .id()));
    }

    @Test
    void theAssignedTechnicianStartsAndSubmitsTheJob() throws Exception {
        call(post("/api/work-orders/{id}/start", workOrder), TestTechnicians.token(technician))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(header().exists(HttpHeaders.ETAG));
        call(post("/api/work-orders/{id}/submit", workOrder), TestTechnicians.token(technician))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"));
    }

    @Test
    void submittingBeforeStartingIs409() throws Exception {
        call(post("/api/work-orders/{id}/submit", workOrder), TestTechnicians.token(technician))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:dispatch:problem:invalid-transition"));
    }

    @Test
    void anotherTechnicianFindsNothingAndCompanyStaffCantStartIt() throws Exception {
        var someoneElse = TestTechnicians.verified(jdbc, 23.79, 90.41, "networking");
        call(post("/api/work-orders/{id}/start", workOrder), TestTechnicians.token(someoneElse))
                .andExpect(status().isNotFound());

        var dispatcher =
                TestTokens.token(c -> c.claim("roles", List.of("DISPATCHER")).claim("tenant_id", tenant.toString()));
        call(post("/api/work-orders/{id}/start", workOrder), dispatcher).andExpect(status().isForbidden());
    }

    private ResultActions call(MockHttpServletRequestBuilder request, String token) throws Exception {
        return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }
}
