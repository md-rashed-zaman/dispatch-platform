package dev.rashed.dispatch.workorder;

import java.time.Instant;
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

    // Lists: newest first, id breaking ties, one page at a time (keyset pagination, T13). Tenant filtering is
    // automatic (@TenantId and row-level security). The row comparison "(created_at, id) < (?, ?)" lets
    // Postgres start reading the index right where the previous page ended, so page 1,000 costs the same
    // as page 1. (Spring Data's keyset scrolling writes it as "a < ? or (a = ? and b < ?)", which the index
    // can't seek to.)
    List<WorkOrder> findAllByOrderByCreatedAtDescIdDesc(Limit limit);

    List<WorkOrder> findByStatusOrderByCreatedAtDescIdDesc(WorkOrderStatus status, Limit limit);

    @Query(
            "select w from WorkOrder w where (w.createdAt, w.id) < (:createdAt, :id) order by w.createdAt desc, w.id desc")
    List<WorkOrder> findPageAfter(Instant createdAt, UUID id, Limit limit);

    @Query("""
            select w from WorkOrder w where w.status = :status and (w.createdAt, w.id) < (:createdAt, :id)
            order by w.createdAt desc, w.id desc""")
    List<WorkOrder> findPageByStatusAfter(WorkOrderStatus status, Instant createdAt, UUID id, Limit limit);
}
