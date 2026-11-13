package dev.rashed.dispatch.workorder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.rashed.dispatch.TestTokens;
import dev.rashed.dispatch.TestcontainersConfiguration;
import dev.rashed.dispatch.tenancy.TenantContext;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
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
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Tenant A must never see or change tenant B's data. Checked through the API, through Hibernate, and with
 * raw SQL that skips Hibernate entirely, so the database layer (row-level security) is proven on its own.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class TenantIsolationTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    WorkOrderRepository workOrders;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate transaction;

    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    UUID workOrderA;
    UUID workOrderB;

    @BeforeEach
    void setUp() {
        for (var tenant : List.of(tenantA, tenantB)) {
            jdbc.update("insert into company (id, name, slug) values (?, 'Company', ?)", tenant, "company-" + tenant);
        }
        workOrderA = createWorkOrder(tenantA, "Printer jam at Gulshan branch");
        workOrderB = createWorkOrder(tenantB, "Router down at Uttara branch");
    }

    @Test
    void ownWorkOrderIsReturned() throws Exception {
        getWorkOrder(workOrderB, dispatcherOf(tenantB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Router down at Uttara branch"));
    }

    @Test
    void anotherTenantsWorkOrderIs404NotForbidden() throws Exception {
        getWorkOrder(workOrderB, dispatcherOf(tenantA)).andExpect(status().isNotFound());
    }

    @Test
    void callerWithoutTenantFindsNothing() throws Exception {
        var noTenant = TestTokens.token(c -> c.claim("roles", List.of("DISPATCHER")));

        getWorkOrder(workOrderA, noTenant).andExpect(status().isNotFound());
    }

    @Test
    void listingReturnsOnlyOwnTenantsWorkOrders() {
        var ids = asTenant(
                tenantA, () -> workOrders.findAll().stream().map(WorkOrder::id).toList());

        assertThat(ids).contains(workOrderA).doesNotContain(workOrderB);
    }

    @Test
    void databaseHidesOtherTenantsRowsFromRawSql() {
        // Native SQL with no tenant filter at all: only row-level security stands in the way.
        var visible = asTenant(
                tenantA,
                () -> jdbc.queryForList(
                        "select id from work_order where id in (?, ?)", UUID.class, workOrderA, workOrderB));
        var checklistOfB = asTenant(
                tenantA,
                () -> jdbc.queryForObject(
                        "select count(*) from checklist_item where work_order_id = ?", Long.class, workOrderB));

        assertThat(visible).containsExactly(workOrderA);
        assertThat(checklistOfB).isZero();
    }

    @Test
    void databaseShowsNothingWhenNoTenantIsSet() {
        var visible = transaction.execute(status -> jdbc.queryForObject(
                "select count(*) from work_order where id in (?, ?)", Long.class, workOrderA, workOrderB));

        assertThat(visible).isZero();
    }

    @Test
    void databaseRejectsWritingRowsForAnotherTenant() {
        assertThatThrownBy(() -> asTenant(
                        tenantA,
                        () -> jdbc.update("update work_order set tenant_id = ? where id = ?", tenantB, workOrderA)))
                .rootCause()
                .hasMessageContaining("violates row-level security policy");
    }

    @Test
    void hibernateRefusesToSaveForAnotherTenant() {
        assertThatThrownBy(() -> asTenant(tenantA, () -> workOrders.save(WorkOrders.draft(tenantB, "Sneaky"))))
                .hasMessageContaining(
                        "assigned tenant id differs from current tenant id"); // caught before any SQL runs
    }

    private UUID createWorkOrder(UUID tenant, String title) {
        return asTenant(tenant, () -> {
            var workOrder = WorkOrders.draft(tenant, title);
            workOrder.addChecklistItem("Check the cables");
            return workOrders.save(workOrder).id();
        });
    }

    private <T> T asTenant(UUID tenant, Supplier<T> work) {
        return TenantContext.callAs(tenant, () -> transaction.execute(status -> work.get()));
    }

    private static String dispatcherOf(UUID tenant) {
        return TestTokens.token(c -> c.claim("roles", List.of("DISPATCHER")).claim("tenant_id", tenant.toString()));
    }

    private ResultActions getWorkOrder(UUID id, String token) throws Exception {
        return mvc.perform(get("/api/work-orders/{id}", id).header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }
}
