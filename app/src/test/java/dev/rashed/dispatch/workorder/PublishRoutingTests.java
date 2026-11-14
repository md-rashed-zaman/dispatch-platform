package dev.rashed.dispatch.workorder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.rashed.dispatch.TestTechnicians;
import dev.rashed.dispatch.TestcontainersConfiguration;
import dev.rashed.dispatch.tenancy.TenantContext;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.test.EnableScenarios;
import org.springframework.modulith.test.Scenario;
import org.springframework.transaction.support.TransactionTemplate;

/** Publishing and routing are decoupled by an event: workorder publishes, matching reacts after commit (T16). */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@EnableScenarios
class PublishRoutingTests {

    @Autowired
    WorkOrderRepository workOrders;

    @Autowired
    WorkOrderRouting routing;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate transaction;

    final UUID tenant = UUID.randomUUID();
    final String skill = TestTechnicians.uniqueSkill();
    UUID nearby;
    UUID alsoNearby;

    @BeforeEach
    void setUp() {
        jdbc.update("insert into company (id, name, slug) values (?, 'Company', ?)", tenant, "company-" + tenant);
        // WorkOrders.draft puts the site in Dhanmondi (23.7465, 90.3760).
        nearby = TestTechnicians.verified(jdbc, 23.7480, 90.3770, skill);
        alsoNearby = TestTechnicians.verified(jdbc, 23.7600, 90.3900, skill);
    }

    @Test
    void publishingLeadsToOffersForTheNearbyTechnicians(Scenario scenario) {
        var workOrder = draftNeeding(skill);

        // The scenario opens its own transaction for the stimulus, so the tenant must be set around all of it.
        TenantContext.runAs(
                tenant,
                () -> scenario.stimulate(() -> {
                            var draft = workOrders.findById(workOrder).orElseThrow();
                            draft.publish();
                            return workOrders.save(draft);
                        })
                        .andWaitForEventOfType(WorkOrderPublished.class)
                        .matching(event -> event.workOrderId().equals(workOrder))
                        .toArriveAndVerify(
                                event -> assertThat(event.requiredSkills()).contains(skill)));

        await().atMost(Duration.ofSeconds(10))
                .until(() -> offeredTechnicians(workOrder).size() == 2);
        assertThat(offeredTechnicians(workOrder)).containsExactlyInAnyOrder(nearby, alsoNearby);
        assertThat(statusOf(workOrder)).isEqualTo("ROUTED");
    }

    /** Events can arrive twice (redelivery after a crash). The second delivery must change nothing. */
    @Test
    void routingTheSameWorkOrderTwiceOffersItOnce() {
        var workOrder = draftNeeding(TestTechnicians.uniqueSkill()); // nobody matches: no automatic routing
        asTenant(() -> {
            var draft = workOrders.findById(workOrder).orElseThrow();
            draft.publish();
            return workOrders.save(draft);
        });

        var first = TenantContext.callAs(tenant, () -> routing.offer(workOrder, List.of(nearby)));
        var second = TenantContext.callAs(tenant, () -> routing.offer(workOrder, List.of(nearby, alsoNearby)));

        assertThat(first).isTrue();
        assertThat(second).isFalse();
        assertThat(offeredTechnicians(workOrder)).containsExactly(nearby);
    }

    /** The reason listeners run after commit: a publish that rolls back must not reach any technician. */
    @Test
    void aPublishThatRollsBackOffersNothing() {
        var workOrder = draftNeeding(skill);

        try {
            asTenant(() -> {
                var draft = workOrders.findById(workOrder).orElseThrow();
                draft.publish();
                workOrders.saveAndFlush(draft);
                throw new IllegalStateException("something later in the same transaction fails");
            });
        } catch (IllegalStateException expected) {
            // rolled back
        }

        await().during(Duration.ofSeconds(2))
                .atMost(Duration.ofSeconds(4))
                .until(() -> offeredTechnicians(workOrder).isEmpty());
        assertThat(statusOf(workOrder)).isEqualTo("DRAFT");
    }

    private UUID draftNeeding(String requiredSkill) {
        return asTenant(() -> {
            var draft = WorkOrder.draft(
                    tenant,
                    "Fix POS terminal",
                    "Terminal shows a communication error.",
                    "House 12, Road 5, Dhanmondi, Dhaka",
                    new dev.rashed.dispatch.shared.GeoPoint(23.7465, 90.3760),
                    List.of(requiredSkill),
                    WorkOrders.draft(tenant, "x").timeWindow(),
                    dev.rashed.dispatch.shared.Money.bdt(250_000));
            draft.addChecklistItem("Photo of the screen");
            return workOrders.save(draft).id();
        });
    }

    private List<UUID> offeredTechnicians(UUID workOrder) {
        return asTenant(() ->
                jdbc.queryForList("select technician_id from offer where work_order_id = ?", UUID.class, workOrder));
    }

    private String statusOf(UUID workOrder) {
        return asTenant(
                () -> jdbc.queryForObject("select status from work_order where id = ?", String.class, workOrder));
    }

    private <T> T asTenant(Supplier<T> work) {
        return TenantContext.callAs(tenant, () -> transaction.execute(s -> work.get()));
    }
}
