package dev.rashed.dispatch.workorder;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface WorkOrderRepository extends JpaRepository<WorkOrder, UUID> {

    /** One SQL statement: work orders and their checklist items joined. */
    @Query("select w from WorkOrder w left join fetch w.checklist where w.tenantId = :tenantId order by w.createdAt")
    List<WorkOrder> findAllWithChecklistByTenantId(UUID tenantId);

    /** Checklists load lazily on first access, in batches (default_batch_fetch_size), not one query each. */
    List<WorkOrder> findByTenantIdOrderByCreatedAt(UUID tenantId);
}
