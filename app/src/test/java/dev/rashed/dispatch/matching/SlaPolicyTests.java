package dev.rashed.dispatch.matching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import dev.rashed.dispatch.TestTechnicians;
import dev.rashed.dispatch.TestTokens;
import dev.rashed.dispatch.TestcontainersConfiguration;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Work orders nobody takes: offered farther away after 2 hours, escalated after 6, each step once, also when
 * two instances run the job at the same moment.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class SlaPolicyTests {

    // The API puts the site in Gulshan (23.7930, 90.4130).
    private static final double SITE_LAT = 23.7930;
    private static final double SITE_LON = 90.4130;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate transaction;

    @Autowired
    SlaPolicy slaPolicy;

    final UUID tenant = UUID.randomUUID();
    final String skill = TestTechnicians.uniqueSkill();
    String dispatcher;

    @BeforeEach
    void setUp() {
        jdbc.update("insert into company (id, name, slug) values (?, 'Company', ?)", tenant, "company-" + tenant);
        dispatcher =
                TestTokens.token(c -> c.claim("roles", List.of("DISPATCHER")).claim("tenant_id", tenant.toString()));
    }

    @Test
    void afterTwoHoursTheWorkOrderIsOfferedToTechniciansFartherAway() throws Exception {
        // 25 km away: outside the first search (20 km), inside the wider one and their own 30 km radius.
        var twentyFiveKmNorth = TestTechnicians.verified(jdbc, SITE_LAT + 0.225, SITE_LON, skill);
        var workOrder = published(); // nobody within 20 km: it stays PUBLISHED
        waitedFor(workOrder, Duration.ofHours(3));

        slaPolicy.run();

        assertThat(state(workOrder)).isEqualTo("ROUTED/WIDENED");
        assertThat(offeredTo(workOrder)).containsExactly(twentyFiveKmNorth);

        slaPolicy.run(); // the next round has nothing to do for it
        assertThat(offeredTo(workOrder)).hasSize(1);
    }

    @Test
    void afterSixHoursTheCompanyIsToldOnce() throws Exception {
        var workOrder = published();
        waitedFor(workOrder, Duration.ofHours(7));

        slaPolicy.run();
        slaPolicy.run();

        assertThat(state(workOrder)).isEqualTo("PUBLISHED/ESCALATED");
        assertThat(breachEvents(workOrder)).isEqualTo(1);
    }

    @Test
    void workThatWasTakenIsLeftAlone() throws Exception {
        var workOrder = published();
        waitedFor(workOrder, Duration.ofHours(7));
        asTenant("update work_order set status = 'CANCELLED' where id = ?", workOrder);

        slaPolicy.run();

        assertThat(state(workOrder)).isEqualTo("CANCELLED/ON_TRACK");
        assertThat(breachEvents(workOrder)).isZero();
    }

    /** Two instances, same moment: ShedLock lets one of them run the round. */
    @Test
    void twoInstancesAtOnceProcessEachBreachOnce() throws Exception {
        var late = lateWorkOrders(5);

        runTwiceAtOnce(slaPolicy::run);

        late.forEach(workOrder -> assertThat(breachEvents(workOrder)).isEqualTo(1));
        assertThat(jdbc.queryForObject("select count(*) from shedlock where name = 'sla-policy'", Integer.class))
                .isEqualTo(1);
    }

    /**
     * The second guard: even without the lock (a round that outlives lockAtMostFor, two jobs started at once),
     * each work order's version lets only one of the two save its change.
     */
    @Test
    void withoutTheLockEachBreachIsStillProcessedOnce() throws Exception {
        var late = lateWorkOrders(5);
        SlaPolicy unlocked = AopTestUtils.getTargetObject(slaPolicy); // the bean behind ShedLock's proxy

        runTwiceAtOnce(unlocked::run);

        late.forEach(workOrder -> assertThat(breachEvents(workOrder)).isEqualTo(1));
    }

    private List<UUID> lateWorkOrders(int count) throws Exception {
        var late = new ArrayList<UUID>();
        for (int i = 0; i < count; i++) {
            var workOrder = published();
            waitedFor(workOrder, Duration.ofHours(7));
            late.add(workOrder);
        }
        return late;
    }

    private static void runTwiceAtOnce(Runnable job) throws Exception {
        var start = new CountDownLatch(1);
        try (var instances = Executors.newFixedThreadPool(2)) {
            var a = instances.submit(() -> {
                start.await();
                job.run();
                return null;
            });
            var b = instances.submit(() -> {
                start.await();
                job.run();
                return null;
            });
            start.countDown();
            a.get();
            b.get();
        }
    }

    private UUID published() throws Exception {
        var created = mvc.perform(post("/api/work-orders")
                        .header("Authorization", "Bearer " + dispatcher)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "CCTV camera offline", "description": "Camera 3 shows no signal.",
                                 "siteAddress": "Gulshan Avenue, Dhaka", "site": {"latitude": 23.7930, "longitude": 90.4130},
                                 "requiredSkills": ["%s"],
                                 "timeWindow": {"startsAt": "2026-12-01T04:00:00Z", "endsAt": "2026-12-01T08:00:00Z"},
                                 "budget": {"minor": 300000, "currency": "BDT"}, "checklist": ["Photo of the camera"]}
                                """.formatted(skill)))
                .andReturn()
                .getResponse();
        assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
        var id = UUID.fromString(JsonPath.read(created.getContentAsString(), "$.id"));
        var publish = mvc.perform(
                        post("/api/work-orders/{id}/publish", id).header("Authorization", "Bearer " + dispatcher))
                .andReturn()
                .getResponse();
        assertThat(publish.getStatus()).as(publish.getContentAsString()).isEqualTo(200);
        return id;
    }

    private void waitedFor(UUID workOrder, Duration waited) {
        asTenant(
                "update work_order set published_at = now() - ? * interval '1 second' where id = ?",
                waited.toSeconds(),
                workOrder);
    }

    /** Row-level security: the test names the tenant the way the app does. */
    private void asTenant(String sql, Object... args) {
        transaction.executeWithoutResult(status -> {
            jdbc.queryForObject("select set_config('app.tenant_id', ?, true)", String.class, tenant.toString());
            jdbc.update(sql, args);
        });
    }

    private String state(UUID workOrder) {
        return transaction.execute(status -> {
            jdbc.queryForObject("select set_config('app.tenant_id', ?, true)", String.class, tenant.toString());
            return jdbc.queryForObject(
                    "select status || '/' || sla_stage from work_order where id = ?", String.class, workOrder);
        });
    }

    private List<UUID> offeredTo(UUID workOrder) {
        return transaction.execute(status -> {
            jdbc.queryForObject("select set_config('app.tenant_id', ?, true)", String.class, tenant.toString());
            return jdbc.queryForList("select technician_id from offer where work_order_id = ?", UUID.class, workOrder);
        });
    }

    private int breachEvents(UUID workOrder) {
        return jdbc.queryForObject(
                "select count(*) from outbox_event where message_key = ? and event_type = 'work-order.sla-breached'",
                Integer.class,
                workOrder.toString());
    }
}
