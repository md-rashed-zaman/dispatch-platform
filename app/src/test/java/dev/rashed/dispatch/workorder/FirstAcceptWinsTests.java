package dev.rashed.dispatch.workorder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import dev.rashed.dispatch.TestTechnicians;
import dev.rashed.dispatch.TestcontainersConfiguration;
import dev.rashed.dispatch.tenancy.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The signature problem (T12, ADR 0005): 50 technicians accept the same job at the same instant, against a
 * real Postgres. Exactly one may win; the database must agree with what the 50 responses said.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class FirstAcceptWinsTests {

    static final int TECHNICIANS = 50;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate transaction;

    @Autowired
    WorkOrderRepository workOrders;

    final UUID tenant = UUID.randomUUID();
    UUID workOrder;
    final Map<UUID, UUID> offerByTechnician = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() {
        jdbc.update("insert into company (id, name, slug) values (?, 'Company', ?)", tenant, "company-" + tenant);
        workOrder = asTenant(() -> workOrders
                .save(WorkOrders.inStatus(tenant, WorkOrderStatus.ROUTED))
                .id());
        var skill = TestTechnicians.uniqueSkill();
        for (var i = 0; i < TECHNICIANS; i++) {
            var technician = TestTechnicians.verified(jdbc, 23.7465, 90.3760, skill);
            var offer = UUID.randomUUID();
            asTenant(() -> jdbc.update("""
                    insert into offer (id, tenant_id, work_order_id, technician_id, status, expires_at)
                    values (?, ?, ?, ?, 'OFFERED', now() + interval '30 minutes')
                    """, offer, tenant, workOrder, technician));
            offerByTechnician.put(technician, offer);
        }
    }

    @RepeatedTest(5)
    void exactlyOneOfFiftySimultaneousAcceptsWins() throws Exception {
        var gate = new CountDownLatch(1);
        var outcomeByTechnician = new ConcurrentHashMap<UUID, String>();
        // Platform threads, one per technician: on a 4-core laptop, virtual threads shared about two carrier
        // threads and reached the database about 20 ms apart, which hid the race the test is here to catch.
        try (var threads = Executors.newFixedThreadPool(TECHNICIANS)) {
            for (var entry : offerByTechnician.entrySet()) {
                var technician = entry.getKey();
                var token = TestTechnicians.token(technician);
                threads.submit(() -> {
                    gate.await(); // all 50 wait here, then go at once
                    try {
                        var status = mvc.perform(post("/api/offers/{id}/accept", entry.getValue())
                                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                                .andReturn()
                                .getResponse()
                                .getStatus();
                        outcomeByTechnician.put(technician, String.valueOf(status));
                    } catch (Exception e) { // a crash must count as an answer, not vanish with the thread
                        outcomeByTechnician.put(technician, "crashed: " + rootMessage(e));
                    }
                    return null;
                });
            }
            gate.countDown();
        } // closing the executor waits for every request to finish

        assertThat(outcomeByTechnician).hasSize(TECHNICIANS);
        var winners = outcomeByTechnician.entrySet().stream()
                .filter(e -> e.getValue().equals("200"))
                .map(Map.Entry::getKey)
                .toList();
        assertThat(winners).hasSize(1);
        assertThat(outcomeByTechnician.values())
                .filteredOn(o -> !o.equals("200"))
                .containsOnly("409");

        // The database tells the same story as the responses.
        var winner = winners.getFirst();
        var row = asTenant(
                () -> jdbc.queryForMap("select status, technician_id from work_order where id = ?", workOrder));
        assertThat(row).containsEntry("status", "ASSIGNED").containsEntry("technician_id", winner);
        List<Map<String, Object>> offers = asTenant(
                () -> jdbc.queryForList("select technician_id, status from offer where work_order_id = ?", workOrder));
        assertThat(offers)
                .hasSize(TECHNICIANS)
                .allSatisfy(offer -> assertThat(offer.get("status"))
                        .isEqualTo(offer.get("technician_id").equals(winner) ? "ACCEPTED" : "EXPIRED"));
    }

    private static String rootMessage(Throwable e) {
        var root = e;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getMessage();
    }

    private <T> T asTenant(Supplier<T> work) {
        return TenantContext.callAs(tenant, () -> transaction.execute(s -> work.get()));
    }
}
