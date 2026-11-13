package dev.rashed.dispatch.workorder;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rashed.dispatch.TestcontainersConfiguration;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Proves how many SQL statements loading a list of work orders costs. The classic N+1 bug is 1 query
 * for the list plus 1 per work order for its checklist: 21 statements for 20 work orders.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@Transactional
class WorkOrderLoadingTests {

    static final int WORK_ORDERS = 20;
    static final int ITEMS_EACH = 3;

    @Autowired
    WorkOrderRepository workOrders;

    @Autowired
    EntityManager entityManager;

    @Autowired
    JdbcTemplate jdbc;

    UUID tenantId;
    Statistics statistics;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        jdbc.update(
                "insert into company (id, name, slug) values (?, ?, ?)",
                tenantId,
                "City Bank",
                "city-bank-" + tenantId);
        for (int i = 0; i < WORK_ORDERS; i++) {
            var workOrder = WorkOrders.draft(tenantId, "Work order " + i);
            for (int j = 0; j < ITEMS_EACH; j++) {
                workOrder.addChecklistItem("Item " + j);
            }
            workOrders.save(workOrder);
        }
        entityManager.flush(); // write everything now
        entityManager.clear(); // and forget it, so the next reads really hit the database

        statistics = entityManager
                .getEntityManagerFactory()
                .unwrap(SessionFactory.class)
                .getStatistics();
        statistics.clear();
    }

    @Test
    void fetchJoinLoadsWorkOrdersAndChecklistsInOneStatement() {
        var loaded = workOrders.findAllWithChecklistByTenantId(tenantId);
        int items = countItems(loaded);

        assertThat(loaded).hasSize(WORK_ORDERS);
        assertThat(items).isEqualTo(WORK_ORDERS * ITEMS_EACH);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
    }

    @Test
    void lazyLoadingWithBatchFetchingNeedsTwoStatementsNotTwentyOne() {
        var loaded = workOrders.findByTenantIdOrderByCreatedAt(tenantId);
        int items = countItems(loaded); // touching checklists triggers lazy loading

        assertThat(items).isEqualTo(WORK_ORDERS * ITEMS_EACH);
        // 1 for the work orders + 1 for all 20 checklists, thanks to default_batch_fetch_size=50.
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
    }

    @Test
    void checklistComesBackInPositionOrder() {
        var loaded = workOrders.findAllWithChecklistByTenantId(tenantId).getFirst();

        assertThat(loaded.checklist())
                .extracting(ChecklistItem::description)
                .containsExactly("Item 0", "Item 1", "Item 2");
    }

    private static int countItems(List<WorkOrder> loaded) {
        return loaded.stream().mapToInt(w -> w.checklist().size()).sum();
    }
}
