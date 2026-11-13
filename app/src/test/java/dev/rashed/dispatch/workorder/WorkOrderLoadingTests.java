package dev.rashed.dispatch.workorder;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rashed.dispatch.TestcontainersConfiguration;
import dev.rashed.dispatch.tenancy.TenantContext;
import jakarta.persistence.EntityManagerFactory;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Proves how many SQL statements loading a list of work orders costs. The classic N+1 bug is 1 query
 * for the list plus 1 per work order for its checklist: 21 statements for 20 work orders.
 *
 * <p>Each step runs in its own transaction as the tenant, like a real request: the tenant must be known
 * when the transaction starts, so a test-wide {@code @Transactional} (started before any test code) can't work.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class WorkOrderLoadingTests {

    static final int WORK_ORDERS = 20;
    static final int ITEMS_EACH = 3;

    @Autowired
    WorkOrderRepository workOrders;

    @Autowired
    EntityManagerFactory entityManagerFactory;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate transaction;

    UUID tenantId;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        jdbc.update(
                "insert into company (id, name, slug) values (?, ?, ?)",
                tenantId,
                "City Bank",
                "city-bank-" + tenantId);
        asTenant(() -> {
            for (int i = 0; i < WORK_ORDERS; i++) {
                var workOrder = WorkOrders.draft(tenantId, "Work order " + i);
                for (int j = 0; j < ITEMS_EACH; j++) {
                    workOrder.addChecklistItem("Item " + j);
                }
                workOrders.save(workOrder);
            }
            return null;
        });
    }

    @Test
    void fetchJoinLoadsWorkOrdersAndChecklistsInOneStatement() {
        var loaded = asTenant(() -> workOrders.findAllWithChecklistByTenantId(tenantId));
        int items = countItems(loaded);

        assertThat(loaded).hasSize(WORK_ORDERS);
        assertThat(items).isEqualTo(WORK_ORDERS * ITEMS_EACH);
        assertThat(statistics().getPrepareStatementCount()).isEqualTo(1);
    }

    @Test
    void lazyLoadingWithBatchFetchingNeedsTwoStatementsNotTwentyOne() {
        // Touching the checklists triggers lazy loading, so it must happen inside the transaction.
        int items = asTenant(() -> countItems(workOrders.findByTenantIdOrderByCreatedAt(tenantId)));

        assertThat(items).isEqualTo(WORK_ORDERS * ITEMS_EACH);
        // 1 for the work orders + 1 for all 20 checklists, thanks to default_batch_fetch_size=50.
        assertThat(statistics().getPrepareStatementCount()).isEqualTo(2);
    }

    @Test
    void checklistComesBackInPositionOrder() {
        var loaded = asTenant(() -> workOrders.findAllWithChecklistByTenantId(tenantId))
                .getFirst();

        assertThat(loaded.checklist())
                .extracting(ChecklistItem::description)
                .containsExactly("Item 0", "Item 1", "Item 2");
    }

    /** Runs {@code work} in a new transaction as the test tenant, counting only the statements it runs. */
    private <T> T asTenant(Supplier<T> work) {
        return TenantContext.callAs(
                tenantId,
                () -> transaction.execute(status -> {
                    statistics().clear();
                    return work.get();
                }));
    }

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    private static int countItems(List<WorkOrder> loaded) {
        return loaded.stream().mapToInt(w -> w.checklist().size()).sum();
    }
}
