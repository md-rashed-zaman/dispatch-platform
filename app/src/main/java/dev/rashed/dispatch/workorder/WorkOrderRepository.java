package dev.rashed.dispatch.workorder;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface WorkOrderRepository extends JpaRepository<WorkOrder, UUID> {

    /** One SQL statement: work orders and their checklist items joined. */
    @Query("select w from WorkOrder w left join fetch w.checklist where w.tenantId = :tenantId order by w.createdAt")
    List<WorkOrder> findAllWithChecklistByTenantId(UUID tenantId);

    /** Checklists load lazily on first access, in batches (default_batch_fetch_size), not one query each. */
    List<WorkOrder> findByTenantIdOrderByCreatedAt(UUID tenantId);

    // Newest first. Tenant filtering is automatic (@TenantId and row-level security).
    // shortcut: a plain limit, no paging past the first page; cursor pagination comes with step 1.5 (T13).
    List<WorkOrder> findAllByOrderByCreatedAtDesc(Limit limit);

    List<WorkOrder> findByStatusOrderByCreatedAtDesc(WorkOrderStatus status, Limit limit);
}
