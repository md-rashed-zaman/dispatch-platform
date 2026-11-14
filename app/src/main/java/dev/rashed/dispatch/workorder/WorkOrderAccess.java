package dev.rashed.dispatch.workorder;

import dev.rashed.dispatch.identity.CurrentUser;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who may work with a work order: its company's staff, and the technician assigned to it. One native query,
 * so it works for technicians too (they have no tenant, so Hibernate's tenant filter would hide everything);
 * row-level security limits what each caller can see.
 */
@Service
public class WorkOrderAccess {

    private final JdbcClient jdbc;

    WorkOrderAccess(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Empty if the work order doesn't exist or the caller may not see it; treat both as 404. */
    @Transactional(readOnly = true)
    public Optional<WorkOrderRef> forCaller(UUID workOrderId) {
        var sql = "select id, tenant_id, status, technician_id from work_order where id = :id";
        var technicianId = CurrentUser.current().map(CurrentUser::technicianId);
        // Row-level security also lets technicians read work merely offered to them; working on it takes
        // being assigned.
        var query = technicianId.isPresent()
                ? jdbc.sql(sql + " and technician_id = :technicianId").param("technicianId", technicianId.get())
                : jdbc.sql(sql);
        return query.param("id", workOrderId)
                .query((rs, rowNum) -> new WorkOrderRef(
                        rs.getObject("id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        WorkOrderStatus.valueOf(rs.getString("status")),
                        rs.getObject("technician_id", UUID.class)))
                .optional();
    }
}
