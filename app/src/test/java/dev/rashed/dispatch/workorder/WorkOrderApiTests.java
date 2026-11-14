package dev.rashed.dispatch.workorder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.rashed.dispatch.TestTokens;
import dev.rashed.dispatch.TestcontainersConfiguration;
import dev.rashed.dispatch.tenancy.TenantContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@RecordApplicationEvents
class WorkOrderApiTests {

    static final String NEW_WORK_ORDER = """
            {
              "title": "POS terminal shows communication error",
              "description": "Card payments fail at counter 2 since this morning.",
              "siteAddress": "House 12, Road 5, Dhanmondi, Dhaka",
              "site": {"latitude": 23.7465, "longitude": 90.3760},
              "requiredSkills": ["pos-terminal", "networking"],
              "timeWindow": {"startsAt": "2026-12-01T04:00:00Z", "endsAt": "2026-12-01T08:00:00Z"},
              "budget": {"minor": 250000, "currency": "BDT"},
              "checklist": ["Photo of the error screen", "Test card payment succeeds"],
              "status": "APPROVED"
            }
            """;

    @Autowired
    MockMvc mvc;

    @Autowired
    WorkOrderRepository workOrders;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate transaction;

    @Autowired
    ApplicationEvents events;

    UUID tenant = UUID.randomUUID();
    UUID technician = UUID.randomUUID();
    String dispatcher;

    @BeforeEach
    void setUp() {
        jdbc.update("insert into company (id, name, slug) values (?, 'Company', ?)", tenant, "company-" + tenant);
        jdbc.update(
                """
                insert into technician (id, full_name, phone, skills, home_latitude, home_longitude,
                    service_radius_km, status)
                values (?, 'Karim', ?, '{networking}', 23.78, 90.40, 20, 'VERIFIED')
                """, technician, "+88017" + String.format("%08d", Math.floorMod(technician.hashCode(), 100_000_000)));
        dispatcher =
                TestTokens.token(c -> c.claim("roles", List.of("DISPATCHER")).claim("tenant_id", tenant.toString()));
    }

    @Test
    void createsADraftAndIgnoresAStatusSentByTheClient() throws Exception {
        call(post("/api/work-orders").contentType(MediaType.APPLICATION_JSON).content(NEW_WORK_ORDER))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, matchesPattern("/api/work-orders/[0-9a-f-]{36}")))
                .andExpect(header().string(HttpHeaders.ETAG, matchesPattern("\"\\d+\"")))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.checklist[1].description").value("Test card payment succeeds"))
                .andExpect(jsonPath("$.version").doesNotExist());
    }

    @Test
    void invalidInputIs400WithEveryBadField() throws Exception {
        var invalid = NEW_WORK_ORDER
                .replace("\"POS terminal shows communication error\"", "\"  \"")
                .replace("\"pos-terminal\"", "\"POS Terminal\"")
                .replace("2026-12-01T08:00:00Z", "2026-12-01T03:00:00Z");

        call(post("/api/work-orders").contentType(MediaType.APPLICATION_JSON).content(invalid))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PROBLEM_JSON_VALUE))
                .andExpect(jsonPath("$.type").value("urn:dispatch:problem:invalid-input"))
                .andExpect(jsonPath("$.errors[*].field")
                        .value(containsInAnyOrder("title", "requiredSkills[]", "timeWindow.endAfterStart")));
    }

    @Test
    void techniciansCantCreateWorkOrders() throws Exception {
        var technician = TestTokens.token(c -> c.claim("roles", List.of("TECHNICIAN")));

        mvc.perform(post("/api/work-orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + technician)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(NEW_WORK_ORDER))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("urn:dispatch:problem:forbidden"));
    }

    @Test
    void invalidQueryParameterIs400NamingIt() throws Exception {
        call(get("/api/work-orders").param("limit", "500"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("limit"))
                .andExpect(jsonPath("$.errors[0].message").value("must be less than or equal to 100"));
    }

    @Test
    void listFiltersByStatus() throws Exception {
        var draft = save(WorkOrderStatus.DRAFT);
        var published = save(WorkOrderStatus.PUBLISHED);

        call(get("/api/work-orders").param("status", "PUBLISHED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].id").value(containsInAnyOrder(published.toString())));
        call(get("/api/work-orders"))
                .andExpect(jsonPath("$.items[*].id").value(containsInAnyOrder(draft.toString(), published.toString())))
                .andExpect(jsonPath("$.next").value(nullValue()));
    }

    @Test
    void listPagesThroughEveryWorkOrderExactlyOnceNewestFirst() throws Exception {
        var older = save(WorkOrderStatus.DRAFT);
        var tied = List.of(save(WorkOrderStatus.DRAFT), save(WorkOrderStatus.DRAFT), save(WorkOrderStatus.DRAFT));
        var newest = save(WorkOrderStatus.PUBLISHED);
        // Three created in the same microsecond (bulk imports do this): the id has to break the tie.
        asTenant(() ->
                jdbc.update("update work_order set created_at = '2026-11-14T04:00:00Z' where id = any(?)", (Object)
                        tied.toArray(UUID[]::new)));
        asTenant(() -> jdbc.update("update work_order set created_at = '2026-11-14T03:00:00Z' where id = ?", older));
        asTenant(() -> jdbc.update("update work_order set created_at = '2026-11-14T05:00:00Z' where id = ?", newest));

        var seen = new ArrayList<String>();
        String after = null;
        do {
            var request = get("/api/work-orders").param("limit", "2");
            if (after != null) {
                request.param("after", after);
            }
            var body = call(request).andReturn().getResponse().getContentAsString();
            seen.addAll(JsonPath.read(body, "$.items[*].id"));
            after = JsonPath.read(body, "$.next");
        } while (after != null);

        var tiedNewestIdFirst = tied.stream()
                .sorted(Comparator.reverseOrder())
                .map(UUID::toString)
                .toList();
        assertThat(seen.getFirst()).isEqualTo(newest.toString());
        assertThat(seen.subList(1, 4)).isEqualTo(tiedNewestIdFirst);
        assertThat(seen.getLast()).isEqualTo(older.toString());
    }

    @Test
    void listWithAnInvalidCursorIs400() throws Exception {
        call(get("/api/work-orders").param("after", "bm90LWEtY3Vyc29y"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:dispatch:problem:invalid-cursor"));
    }

    @Test
    void secondEditBasedOnAnOldReadIsRejectedNotSilentlyApplied() throws Exception {
        var id = save(WorkOrderStatus.DRAFT);
        // Karim and Nadia both open the same draft and get the same version...
        var etagBothRead = eTagOf(id);

        // ...Karim saves first, which moves it to a new version...
        call(revise(id, etagBothRead, "{\"title\": \"Edited by Karim\"}")).andExpect(status().isOk());
        assertThat(eTagOf(id)).isNotEqualTo(etagBothRead);

        // ...so Nadia's save, made on her stale copy, gets 412 instead of overwriting Karim's change.
        call(revise(id, etagBothRead, "{\"title\": \"Edited by Nadia\"}"))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.type").value("urn:dispatch:problem:stale-version"));
        call(get("/api/work-orders/{id}", id)).andExpect(jsonPath("$.title").value("Edited by Karim"));
    }

    @Test
    void editWithoutIfMatchIs428() throws Exception {
        var id = save(WorkOrderStatus.DRAFT);

        call(patch("/api/work-orders/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \"x\"}"))
                .andExpect(status().isPreconditionRequired());
    }

    @Test
    void publishedWorkOrdersCantBeEdited() throws Exception {
        var id = save(WorkOrderStatus.PUBLISHED);

        call(revise(id, eTagOf(id), "{\"title\": \"Too late\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:dispatch:problem:business-rule"));
    }

    @Test
    void publishMovesTheDraftOnAndAnnouncesIt() throws Exception {
        var id = save(WorkOrderStatus.DRAFT);

        call(post("/api/work-orders/{id}/publish", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"));

        assertThat(events.stream(WorkOrderStatusChanged.class))
                .extracting(
                        WorkOrderStatusChanged::workOrderId, WorkOrderStatusChanged::from, WorkOrderStatusChanged::to)
                .containsExactly(tuple(id, WorkOrderStatus.DRAFT, WorkOrderStatus.PUBLISHED));
    }

    @Test
    void approvingACancelledWorkOrderIs409WithProblemDetails() throws Exception {
        var id = save(WorkOrderStatus.CANCELLED);

        call(post("/api/work-orders/{id}/approve", id))
                .andExpect(status().isConflict())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PROBLEM_JSON_VALUE))
                .andExpect(jsonPath("$.type").value("urn:dispatch:problem:invalid-transition"))
                .andExpect(jsonPath("$.detail").value("Can't approve a work order that is CANCELLED"))
                .andExpect(jsonPath("$.currentStatus").value("CANCELLED"))
                .andExpect(jsonPath("$.action").value("approve"));
    }

    @Test
    void rejectingSendsTheWorkBackWithTheReason() throws Exception {
        var id = save(WorkOrderStatus.SUBMITTED);

        call(post("/api/work-orders/{id}/reject", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"Photo of the receipt is missing\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.rejectionReason").value("Photo of the receipt is missing"));
        call(post("/api/work-orders/{id}/reject", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownWorkOrderIs404ProblemDetails() throws Exception {
        call(post("/api/work-orders/{id}/publish", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:dispatch:problem:not-found"));
    }

    /**
     * The If-Match check can't catch two requests that both read version 0 at the same moment: both pass
     * it. The @Version column does: the UPDATE of the slower one matches no row and fails.
     */
    @Test
    void simultaneousChangesCantBothWin() {
        var id = save(WorkOrderStatus.DRAFT);
        var separateTransaction = new TransactionTemplate(transaction.getTransactionManager());
        separateTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        assertThatThrownBy(() -> asTenant(() -> {
                    var slow = workOrders.findById(id).orElseThrow(); // reads version 0
                    // Meanwhile another request publishes it and commits (version 1).
                    separateTransaction.executeWithoutResult(
                            status -> workOrders.findById(id).orElseThrow().publish());
                    slow.cancel(); // still looks like a DRAFT to this request
                    return workOrders.saveAndFlush(slow);
                }))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        assertThat(asTenant(() -> workOrders.findById(id).orElseThrow().status()))
                .isEqualTo(WorkOrderStatus.PUBLISHED);
    }

    private UUID save(WorkOrderStatus status) {
        return asTenant(() ->
                workOrders.save(WorkOrders.inStatus(tenant, technician, status)).id());
    }

    private String eTagOf(UUID id) throws Exception {
        return call(get("/api/work-orders/{id}", id)).andReturn().getResponse().getHeader(HttpHeaders.ETAG);
    }

    private <T> T asTenant(Supplier<T> work) {
        return TenantContext.callAs(tenant, () -> transaction.execute(s -> work.get()));
    }

    private static MockHttpServletRequestBuilder revise(UUID id, String ifMatch, String json) {
        return patch("/api/work-orders/{id}", id)
                .header(HttpHeaders.IF_MATCH, ifMatch)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json);
    }

    private ResultActions call(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + dispatcher));
    }
}
