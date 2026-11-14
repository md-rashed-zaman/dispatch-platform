package dev.rashed.dispatch;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * The database enforces the critical rules on its own, so raw SQL or a bug elsewhere can't store invalid
 * data. These tests bypass the Java domain model on purpose and write plain SQL.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class SchemaConstraintsTests {

    @Autowired
    JdbcTemplate jdbc;

    UUID companyId = UUID.randomUUID();
    UUID workOrderId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        jdbc.update("insert into company (id, name, slug) values (?, 'Acme', ?)", companyId, "acme-" + companyId);
        // Row-level security: this transaction works for Acme (the app does this when a transaction starts).
        jdbc.queryForObject("select set_config('app.tenant_id', ?, true)", String.class, companyId.toString());
        insertWorkOrder(workOrderId, "ROUTED", null);
    }

    @Test
    void rejectsUnknownStatus() {
        assertThatThrownBy(() -> insertWorkOrder(UUID.randomUUID(), "APROVED", null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("work_order_status_check");
    }

    @Test
    void rejectsAssignedWorkOrderWithoutTechnician() {
        assertThatThrownBy(() -> insertWorkOrder(UUID.randomUUID(), "ASSIGNED", null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("work_order_assigned_has_technician");
    }

    @Test
    void rejectsSecondAcceptedOfferForSameWorkOrder() {
        insertOffer(insertTechnician("+8801711111111"), "ACCEPTED");

        assertThatThrownBy(() -> insertOffer(insertTechnician("+8801722222222"), "ACCEPTED"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("offer_one_accepted_per_work_order");
    }

    private void insertWorkOrder(UUID id, String status, UUID technicianId) {
        jdbc.update("""
                insert into work_order (id, tenant_id, title, description, status, site_address, site_latitude,
                    site_longitude, required_skills, window_starts_at, window_ends_at, budget_minor, budget_currency,
                    technician_id)
                values (?, ?, 'Fix router', 'Router down', ?, 'Gulshan 2, Dhaka', 23.7925, 90.4078,
                    '{networking}', now(), now() + interval '2 hours', 150000, 'BDT', ?)
                """, id, companyId, status, technicianId);
    }

    private UUID insertTechnician(String phone) {
        var id = UUID.randomUUID();
        jdbc.update("""
                insert into technician (id, full_name, phone, skills, home_latitude, home_longitude,
                    service_radius_km, status)
                values (?, 'Karim', ?, '{networking}', 23.78, 90.40, 20, 'VERIFIED')
                """, id, phone);
        return id;
    }

    private void insertOffer(UUID technicianId, String status) {
        jdbc.update("""
                insert into offer (id, tenant_id, work_order_id, technician_id, status, expires_at)
                values (?, ?, ?, ?, ?, now() + interval '15 minutes')
                """, UUID.randomUUID(), companyId, workOrderId, technicianId, status);
    }
}
