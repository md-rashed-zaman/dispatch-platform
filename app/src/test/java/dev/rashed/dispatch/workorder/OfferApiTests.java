package dev.rashed.dispatch.workorder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.rashed.dispatch.TestTechnicians;
import dev.rashed.dispatch.TestTokens;
import dev.rashed.dispatch.TestcontainersConfiguration;
import dev.rashed.dispatch.tenancy.TenantContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

/** The whole flow through the API: a company publishes, technicians nearby get offers, one accepts. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class OfferApiTests {

    // Gulshan 1, Dhaka
    static final double SITE_LATITUDE = 23.7925;
    static final double SITE_LONGITUDE = 90.4125;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate transaction;

    final UUID tenant = UUID.randomUUID();
    final String skill = TestTechnicians.uniqueSkill();
    String dispatcher;
    UUID rahim;
    UUID karim;

    @BeforeEach
    void setUp() {
        jdbc.update("insert into company (id, name, slug) values (?, 'Company', ?)", tenant, "company-" + tenant);
        dispatcher =
                TestTokens.token(c -> c.claim("roles", List.of("DISPATCHER")).claim("tenant_id", tenant.toString()));
        rahim = TestTechnicians.verified(jdbc, 23.7950, 90.4140, skill, "networking"); // about 300 m away
        karim = TestTechnicians.verified(jdbc, 23.8100, 90.4200, skill); // about 2 km away
    }

    @Test
    void publishingOffersTheJobToNearbyTechniciansWithTheSkills() throws Exception {
        var unskilled = TestTechnicians.verified(jdbc, SITE_LATITUDE, SITE_LONGITUDE, "networking");
        var farAway = TestTechnicians.verified(jdbc, 22.3569, 91.7832, skill); // Chattogram

        var workOrder = publishedWorkOrder();

        call(get("/api/work-orders/{id}", workOrder), dispatcher)
                .andExpect(jsonPath("$.status").value("ROUTED"));
        for (var technician : List.of(rahim, karim)) {
            call(get("/api/me/offers"), TestTechnicians.token(technician))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[*].workOrderId").value(contains(workOrder.toString())))
                    .andExpect(jsonPath("$.items[0].status").value("OFFERED"))
                    .andExpect(jsonPath("$.items[0].title").value("Branch network down"))
                    .andExpect(jsonPath("$.items[0].budget.minor").value(250000))
                    .andExpect(jsonPath("$.items[0].budget.positive").doesNotExist());
        }
        for (var technician : List.of(unskilled, farAway)) {
            call(get("/api/me/offers"), TestTechnicians.token(technician))
                    .andExpect(jsonPath("$.items").value(empty()));
        }
    }

    @Test
    void withNobodyInRangeTheWorkOrderStaysPublished() throws Exception {
        var workOrder = publish(createWorkOrder(TestTechnicians.uniqueSkill()));

        call(get("/api/work-orders/{id}", workOrder), dispatcher)
                .andExpect(jsonPath("$.status").value("PUBLISHED"));
    }

    @Test
    void acceptingAssignsTheJobAndClosesEveryOtherOffer() throws Exception {
        var workOrder = publishedWorkOrder();
        var eTagBefore = eTagOf(workOrder);

        call(post("/api/offers/{id}/accept", offerOf(rahim)), TestTechnicians.token(rahim))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.workOrderId").value(workOrder.toString()));

        call(get("/api/work-orders/{id}", workOrder), dispatcher)
                .andExpect(jsonPath("$.status").value("ASSIGNED"))
                .andExpect(jsonPath("$.technicianId").value(rahim.toString()))
                .andExpect(header().string(HttpHeaders.ETAG, org.hamcrest.Matchers.not(eTagBefore)));
        call(get("/api/me/offers"), TestTechnicians.token(karim))
                .andExpect(jsonPath("$.items").value(empty()));
        assertThat(offerStatuses(workOrder)).containsExactlyInAnyOrder("ACCEPTED", "EXPIRED");
    }

    @Test
    void theSecondTechnicianToAcceptHearsItIsTaken() throws Exception {
        publishedWorkOrder();
        var karimsOffer = offerOf(karim);
        call(post("/api/offers/{id}/accept", offerOf(rahim)), TestTechnicians.token(rahim))
                .andExpect(status().isOk());

        call(post("/api/offers/{id}/accept", karimsOffer), TestTechnicians.token(karim))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:dispatch:problem:already-taken"));
    }

    /** A phone on a bad connection retries; the winner must not be told they lost. */
    @Test
    void acceptingAnOfferYouAlreadyWonReturnsItAgain() throws Exception {
        var workOrder = publishedWorkOrder();
        var offer = offerOf(rahim);
        call(post("/api/offers/{id}/accept", offer), TestTechnicians.token(rahim))
                .andExpect(status().isOk());

        call(post("/api/offers/{id}/accept", offer), TestTechnicians.token(rahim))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
        call(get("/api/work-orders/{id}", workOrder), dispatcher)
                .andExpect(jsonPath("$.technicianId").value(rahim.toString()));
    }

    @Test
    void anotherTechniciansOfferIsNotFound() throws Exception {
        publishedWorkOrder();

        call(post("/api/offers/{id}/accept", offerOf(rahim)), TestTechnicians.token(karim))
                .andExpect(status().isNotFound());
    }

    @Test
    void anExpiredOfferCantBeAccepted() throws Exception {
        var workOrder = publishedWorkOrder();
        var offer = offerOf(rahim);
        asTenant(() -> jdbc.update("update offer set expires_at = now() - interval '1 minute' where id = ?", offer));

        call(post("/api/offers/{id}/accept", offer), TestTechnicians.token(rahim))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:dispatch:problem:offer-closed"));
        call(get("/api/work-orders/{id}", workOrder), dispatcher)
                .andExpect(jsonPath("$.status").value("ROUTED"));
        call(get("/api/me/offers"), TestTechnicians.token(rahim))
                .andExpect(jsonPath("$.items").value(empty()));
    }

    @Test
    void onlyTechniciansAcceptOffers() throws Exception {
        publishedWorkOrder();

        call(post("/api/offers/{id}/accept", offerOf(rahim)), dispatcher)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("urn:dispatch:problem:forbidden"));
        // A technician role without a technician profile in the token can't act as any technician.
        call(get("/api/me/offers"), TestTokens.token(c -> c.claim("roles", List.of("TECHNICIAN"))))
                .andExpect(status().isForbidden());
    }

    private UUID publishedWorkOrder() throws Exception {
        return publish(createWorkOrder(skill));
    }

    private UUID createWorkOrder(String requiredSkill) throws Exception {
        var body = """
                {
                  "title": "Branch network down",
                  "description": "No internet at the branch since 9am.",
                  "siteAddress": "Gulshan Avenue, Dhaka",
                  "site": {"latitude": %s, "longitude": %s},
                  "requiredSkills": ["%s"],
                  "timeWindow": {"startsAt": "2026-12-01T04:00:00Z", "endsAt": "2026-12-01T08:00:00Z"},
                  "budget": {"minor": 250000, "currency": "BDT"},
                  "checklist": ["Photo of the router lights"]
                }
                """.formatted(SITE_LATITUDE, SITE_LONGITUDE, requiredSkill);
        var response = call(
                        post("/api/work-orders")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body),
                        dispatcher)
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(JsonPath.read(response, "$.id"));
    }

    private UUID publish(UUID workOrder) throws Exception {
        call(post("/api/work-orders/{id}/publish", workOrder), dispatcher).andExpect(status().isOk());
        return workOrder;
    }

    private UUID offerOf(UUID technician) throws Exception {
        var body = call(get("/api/me/offers"), TestTechnicians.token(technician))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.items[0].id"));
    }

    private List<String> offerStatuses(UUID workOrder) {
        return asTenant(
                () -> jdbc.queryForList("select status from offer where work_order_id = ?", String.class, workOrder));
    }

    private String eTagOf(UUID workOrder) throws Exception {
        return call(get("/api/work-orders/{id}", workOrder), dispatcher)
                .andReturn()
                .getResponse()
                .getHeader(HttpHeaders.ETAG);
    }

    private <T> T asTenant(java.util.function.Supplier<T> work) {
        return TenantContext.callAs(tenant, () -> transaction.execute(s -> work.get()));
    }

    private ResultActions call(MockHttpServletRequestBuilder request, String token) throws Exception {
        return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }
}
