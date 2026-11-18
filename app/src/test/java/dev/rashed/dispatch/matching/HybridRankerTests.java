package dev.rashed.dispatch.matching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.rashed.dispatch.TestTechnicians;
import dev.rashed.dispatch.TestTokens;
import dev.rashed.dispatch.TestcontainersConfiguration;
import dev.rashed.dispatch.ai.Embeddings;
import dev.rashed.dispatch.shared.GeoPoint;
import dev.rashed.dispatch.tenancy.TenantContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Hybrid ranking with scripted vectors: meaning beats a closer technician with the same tag, ranking still works
 * without vectors, and the explain endpoint shows every component. Each test has its own site and skill, so
 * technicians other tests leave in the shared database don't interfere.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class HybridRankerTests {

    static final String MODEL = "test-embedding";

    @MockitoBean
    Embeddings embeddings;

    @Autowired
    HybridRanker ranker;

    @Autowired
    EmbeddingStore store;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate transaction;

    GeoPoint site;
    String skill;
    UUID specialist; // 10 km away, profile means the same as the job
    UUID generalist; // 1 km away, same tag, profile about something else
    UUID outsider; // 2 km away, no matching tag

    @BeforeEach
    void setUp() {
        given(embeddings.model()).willReturn(MODEL);
        site = new GeoPoint(
                21.5 + ThreadLocalRandom.current().nextDouble(0.5),
                92.0 + ThreadLocalRandom.current().nextDouble(0.3));
        skill = TestTechnicians.uniqueSkill();
        specialist = TestTechnicians.verified(jdbc, site.latitude() + 0.09, site.longitude(), skill);
        generalist = TestTechnicians.verified(jdbc, site.latitude() + 0.009, site.longitude(), skill);
        outsider = TestTechnicians.verified(jdbc, site.latitude() + 0.018, site.longitude(), "solar");
        store.saveTechnician(specialist, axis(0), MODEL, "s");
        store.saveTechnician(generalist, axis(1), MODEL, "g");
        store.saveTechnician(outsider, axis(0), MODEL, "o");
    }

    @Test
    void meaningOutranksACloserTechnicianWithTheSameTag() {
        var matches = ranker.rank(site, 20, List.of(skill), Optional.of(axis(0)), 10);

        assertThat(matches).extracting(HybridRanker.Match::technicianId).containsExactly(specialist, generalist);
        var best = matches.getFirst();
        assertThat(best.semantic()).isEqualTo(1.0); // the most similar in the pool
        assertThat(matches.get(1).semantic()).isEqualTo(0.0); // the least
        assertThat(best.skillCoverage()).isEqualTo(1.0);
        assertThat(best.distanceKm()).isCloseTo(10.0, within(0.2));
        assertThat(best.reliability()).isEqualTo(0.5); // no jobs yet: (0 + 1) / (0 + 2)
        assertThat(best.rating()).isEqualTo(0.6); // not yet rated: 3 of 5
        // The outsider is semantically perfect but lacks the required skill (min-skill-coverage 0.5).
    }

    @Test
    void aTechnicianNotYetEmbeddedDoesntOutrankAMeasuredMatch() {
        // Closer than the specialist and with the same tag, but no vector yet: counted as the pool's midpoint.
        var unknown = TestTechnicians.verified(jdbc, site.latitude() + 0.005, site.longitude(), skill);

        var matches = ranker.rank(site, 20, List.of(skill), Optional.of(axis(0)), 10);

        assertThat(matches)
                .extracting(HybridRanker.Match::technicianId)
                .containsExactly(specialist, unknown, generalist);
        assertThat(matches.get(1).semantic()).isNull(); // explain still says nothing was measured
    }

    @Test
    void withoutAJobVectorRankingGoesOnWithoutTheSemanticPart() {
        var matches = ranker.rank(site, 20, List.of(skill), Optional.empty(), 10);

        assertThat(matches).extracting(HybridRanker.Match::technicianId).containsExactly(generalist, specialist);
        assertThat(matches).allSatisfy(m -> assertThat(m.semantic()).isNull());
    }

    @Test
    void reliabilityCountsJobsAcrossCompaniesThroughTheNarrowFunction() {
        var companyA = company();
        var companyB = company();
        workOrder(companyA, specialist, "APPROVED");
        workOrder(companyB, specialist, "APPROVED");
        workOrder(companyB, specialist, "ASSIGNED");

        // Asked as company A, whose own row-level security hides company B's work orders.
        var completion = TenantContext.callAs(companyA, () -> store.completion(List.of(specialist)));

        assertThat(completion.get(specialist)).isEqualTo(new EmbeddingStore.Completion(3, 2));
        Integer visible = TenantContext.callAs(
                companyA,
                () -> transaction.execute(status -> jdbc.queryForObject(
                        "select count(*) from work_order where technician_id = ?", Integer.class, specialist)));
        assertThat(visible).isEqualTo(1);
    }

    @Test
    void theExplainEndpointShowsEveryComponentForTheCompanysOwnWorkOrder() throws Exception {
        given(embeddings.embed(anyString(), any(), anyList())).willReturn(Optional.of(List.of(axis(0))));
        var company = company();
        var dispatcher =
                TestTokens.token(c -> c.claim("roles", List.of("DISPATCHER")).claim("tenant_id", company.toString()));
        var created = mvc.perform(post("/api/work-orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + dispatcher)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "Canon copier jams", "description": "Jams on every page.",
                                 "siteAddress": "Test site", "site": {"latitude": %s, "longitude": %s},
                                 "requiredSkills": ["%s"],
                                 "timeWindow": {"startsAt": "2026-12-01T04:00:00Z", "endsAt": "2026-12-01T08:00:00Z"},
                                 "budget": {"minor": 250000, "currency": "BDT"}, "checklist": ["Photo"]}
                                """.formatted(site.latitude(), site.longitude(), skill)))
                .andExpect(status().isCreated())
                .andReturn();
        String id = JsonPath.read(created.getResponse().getContentAsString(), "$.id");

        mvc.perform(get("/api/work-orders/{id}/matches/explain", id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + dispatcher))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.strategy").value("HYBRID"))
                .andExpect(jsonPath("$.semantic").value(true))
                .andExpect(jsonPath("$.weights.semantic").value(0.5))
                .andExpect(jsonPath("$.matches[0].technicianId").value(specialist.toString()))
                .andExpect(jsonPath("$.matches[0].semantic").value(1.0))
                .andExpect(jsonPath("$.matches[0].skillCoverage").value(1.0))
                .andExpect(jsonPath("$.matches[1].technicianId").value(generalist.toString()));

        var otherCompany = TestTokens.token(c -> c.claim("roles", List.of("DISPATCHER"))
                .claim("tenant_id", company().toString()));
        mvc.perform(get("/api/work-orders/{id}/matches/explain", id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherCompany))
                .andExpect(status().isNotFound());
    }

    private UUID company() {
        var id = UUID.randomUUID();
        jdbc.update("insert into company (id, name, slug) values (?, 'Company', ?)", id, "company-" + id);
        return id;
    }

    private void workOrder(UUID tenant, UUID technician, String status) {
        TenantContext.runAs(
                tenant,
                () -> transaction.executeWithoutResult(
                        tx -> jdbc.update("""
                insert into work_order (id, tenant_id, title, description, status, site_address, site_latitude,
                    site_longitude, required_skills, window_starts_at, window_ends_at, budget_minor, budget_currency,
                    technician_id)
                values (?, ?, 'Job', 'Done before', ?, 'Somewhere', 23.8, 90.4, '{printer}',
                    now(), now() + interval '4 hours', 100000, 'BDT', ?)
                """, UUID.randomUUID(), tenant, status, technician)));
    }

    /** A unit vector along one axis: two axes are unrelated (cosine 0), the same axis is identical (1). */
    static float[] axis(int i) {
        var vector = new float[768];
        vector[i] = 1;
        return vector;
    }
}
