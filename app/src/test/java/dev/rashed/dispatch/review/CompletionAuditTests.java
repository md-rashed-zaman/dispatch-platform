package dev.rashed.dispatch.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.rashed.dispatch.KafkaTestcontainersConfiguration;
import dev.rashed.dispatch.TestTechnicians;
import dev.rashed.dispatch.TestTokens;
import dev.rashed.dispatch.TestcontainersConfiguration;
import dev.rashed.dispatch.ai.CompletionCheck;
import dev.rashed.dispatch.ai.CompletionCheck.Verdict;
import dev.rashed.dispatch.tenancy.TenantContext;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Submit -> outbox -> Kafka -> audit -> policy, with a scripted AI check and everything else real (Postgres,
 * SeaweedFS, Kafka). The policy approves only a confident all-MET assessment; everything else waits for a person,
 * whose decision is recorded against what the AI said.
 */
@Import({TestcontainersConfiguration.class, KafkaTestcontainersConfiguration.class})
@SpringBootTest(properties = "dispatch.review.threshold=0.9")
@AutoConfigureMockMvc
class CompletionAuditTests {

    @MockitoBean
    CompletionCheck check;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate transaction;

    @Autowired
    S3Client s3;

    @Autowired
    KafkaListenerEndpointRegistry listeners;

    @org.springframework.beans.factory.annotation.Value("${dispatch.storage.bucket}")
    String bucket;

    final UUID tenant = UUID.randomUUID();
    UUID technician;
    UUID workOrder;
    String dispatcher;

    @BeforeEach
    void setUp() {
        jdbc.update("insert into company (id, name, slug) values (?, 'Company', ?)", tenant, "company-" + tenant);
        dispatcher =
                TestTokens.token(c -> c.claim("roles", List.of("DISPATCHER")).claim("tenant_id", tenant.toString()));
        technician = TestTechnicians.verified(jdbc, 23.79, 90.41, "cctv");
        workOrder = inProgressWorkOrder();
        var container = listeners.getListenerContainer(CompletionAuditor.CONSUMER);
        await().atMost(Duration.ofSeconds(30))
                .until(() -> container.getAssignedPartitions() != null
                        && !container.getAssignedPartitions().isEmpty());
    }

    @Test
    void aConfidentAllMetAssessmentIsApprovedByTheSystem() throws Exception {
        photo("camera.jpg");
        given(check.check(any())).willReturn(assessment(0.97, Verdict.MET, Verdict.MET));

        submit("Mounted on the corner bracket, feed tested.");

        await().atMost(Duration.ofSeconds(30)).until(() -> "APPROVED".equals(workOrderStatus()));
        var audit = audit();
        assertThat(audit).containsEntry("state", "AUTO_APPROVED").containsEntry("decided_by", "system");
        assertThat(audit.get("reason").toString()).contains("All 2 checklist items are shown", "97% sure");

        var sent = ArgumentCaptor.forClass(CompletionCheck.Input.class);
        verify(check).check(sent.capture());
        assertThat(sent.getValue().notes()).isEqualTo("Mounted on the corner bracket, feed tested.");
        assertThat(sent.getValue().checklist())
                .extracting(CompletionCheck.ChecklistItem::description)
                .containsExactly("Photo of the camera mounted", "Photo of the live feed");
        assertThat(sent.getValue().photos()).hasSize(1);
    }

    @Test
    void doubtGoesToTheQueueAndTheReviewersDecisionIsRecorded() throws Exception {
        photo("camera.jpg");
        given(check.check(any())).willReturn(assessment(0.95, Verdict.MET, Verdict.UNCLEAR));
        submit(null);
        await().atMost(Duration.ofSeconds(30)).until(() -> audit() != null);
        assertThat(workOrderStatus()).isEqualTo("SUBMITTED");

        mvc.perform(get("/api/reviews").header(HttpHeaders.AUTHORIZATION, "Bearer " + dispatcher))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.workOrderId == '%s')].reason".formatted(workOrder))
                        .value(org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.startsWith("Item 2 is unclear"))));
        var auditId = audit().get("id").toString();

        mvc.perform(post("/api/reviews/{id}/approve", auditId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + dispatcher))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("APPROVED"));
        assertThat(workOrderStatus()).isEqualTo("APPROVED");
        // The consumer then reads the APPROVED event; the audit stays credited to the reviewer.
        await().during(Duration.ofSeconds(2))
                .atMost(Duration.ofSeconds(4))
                .until(() -> "APPROVED".equals(audit().get("state")));
        assertThat(audit().get("decided_by").toString()).isNotEqualTo("system").isNotEqualTo("work order");

        mvc.perform(post("/api/reviews/{id}/approve", auditId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + dispatcher))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:dispatch:problem:already-decided"));
    }

    @Test
    void aReviewerCanSendTheWorkBackWithAReason() throws Exception {
        photo("camera.jpg");
        given(check.check(any())).willReturn(assessment(0.6, Verdict.NOT_MET, Verdict.MET));
        submit(null);
        await().atMost(Duration.ofSeconds(30)).until(() -> audit() != null);

        mvc.perform(post("/api/reviews/{id}/reject", audit().get("id"))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + dispatcher)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"The camera is still on the floor.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("REJECTED"))
                .andExpect(jsonPath("$.decisionReason").value("The camera is still on the floor."));
        assertThat(workOrderStatus()).isEqualTo("IN_PROGRESS");
    }

    @Test
    void withoutPhotosNothingIsAskedAndAPersonDecides() throws Exception {
        submit("Done.");

        await().atMost(Duration.ofSeconds(30)).until(() -> audit() != null);
        assertThat(audit()).containsEntry("state", "NEEDS_REVIEW").containsEntry("photo_count", 0);
        assertThat(audit().get("reason").toString()).startsWith("No photos");
        verify(check, never()).check(any());
    }

    @Test
    void approvingTheWorkOrderDirectlyClosesItsAudit() throws Exception {
        photo("camera.jpg");
        given(check.check(any())).willReturn(Optional.empty()); // the AI couldn't answer
        submit(null);
        await().atMost(Duration.ofSeconds(30)).until(() -> audit() != null);

        mvc.perform(post("/api/work-orders/{id}/approve", workOrder)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + dispatcher))
                .andExpect(status().isOk());

        await().atMost(Duration.ofSeconds(30)).until(() -> "DECIDED_ELSEWHERE".equals(audit().get("state")));
        assertThat(audit()).containsEntry("decision_reason", "APPROVED");
        verify(check, times(1)).check(any());
    }

    @Test
    void anotherCompanySeesNeitherTheAuditNorTheQueueEntry() throws Exception {
        submit(null);
        await().atMost(Duration.ofSeconds(30)).until(() -> audit() != null);

        var other = UUID.randomUUID();
        jdbc.update("insert into company (id, name, slug) values (?, 'Other', ?)", other, "company-" + other);
        var outsider =
                TestTokens.token(c -> c.claim("roles", List.of("DISPATCHER")).claim("tenant_id", other.toString()));
        mvc.perform(get("/api/work-orders/{id}/audit", workOrder)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + outsider))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/reviews/{id}/approve", audit().get("id"))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + outsider))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/reviews").header(HttpHeaders.AUTHORIZATION, "Bearer " + outsider))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    private void submit(String notes) throws Exception {
        var request = post("/api/work-orders/{id}/submit", workOrder)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTechnicians.token(technician));
        if (notes != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content("{\"notes\": \"" + notes + "\"}");
        }
        mvc.perform(request).andExpect(status().isOk());
    }

    private UUID inProgressWorkOrder() {
        var id = UUID.randomUUID();
        asTenant(() -> {
            jdbc.update("""
                    insert into work_order (id, tenant_id, title, description, status, site_address, site_latitude,
                        site_longitude, required_skills, window_starts_at, window_ends_at, budget_minor,
                        budget_currency, technician_id)
                    values (?, ?, 'Mount a CCTV camera', 'On the outer wall', 'IN_PROGRESS', 'Gulshan, Dhaka', 23.79,
                        90.41, '{cctv}', now(), now() + interval '4 hours', 250000, 'BDT', ?)
                    """, id, tenant, technician);
            jdbc.update(
                    "insert into checklist_item (id, work_order_id, position, description) values (?, ?, 0, 'Photo of the camera mounted')",
                    UUID.randomUUID(),
                    id);
            jdbc.update(
                    "insert into checklist_item (id, work_order_id, position, description) values (?, ?, 1, 'Photo of the live feed')",
                    UUID.randomUUID(),
                    id);
            return null;
        });
        return id;
    }

    /** A confirmed photo, as if uploaded through the API. */
    private void photo(String name) {
        var key = "tenants/" + tenant + "/work-orders/" + workOrder + "/" + UUID.randomUUID() + "-" + name;
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2, 3};
        s3.putObject(p -> p.bucket(bucket).key(key).contentType("image/jpeg"), RequestBody.fromBytes(jpeg));
        asTenant(() -> jdbc.update(
                """
                insert into attachment (id, tenant_id, work_order_id, object_key, file_name, content_type, declared_size,
                    size, status, uploaded_by, confirmed_at)
                values (?, ?, ?, ?, ?, 'image/jpeg', ?, ?, 'CONFIRMED', ?, now())
                """, UUID.randomUUID(), tenant, workOrder, key, name, jpeg.length, jpeg.length, technician));
    }

    private String workOrderStatus() {
        return asTenant(
                () -> jdbc.queryForObject("select status from work_order where id = ?", String.class, workOrder));
    }

    private Map<String, Object> audit() {
        return asTenant(() -> jdbc
                .queryForList(
                        "select * from completion_audit where work_order_id = ? order by created_at desc", workOrder)
                .stream()
                .findFirst()
                .orElse(null));
    }

    private <T> T asTenant(Supplier<T> work) {
        return TenantContext.callAs(tenant, () -> transaction.execute(s -> work.get()));
    }

    private static Optional<CompletionCheck.Assessment> assessment(double confidence, Verdict... verdicts) {
        var items = new java.util.ArrayList<CompletionCheck.Item>();
        for (int i = 0; i < verdicts.length; i++) {
            items.add(new CompletionCheck.Item(i, verdicts[i], "evidence for item " + i));
        }
        return Optional.of(new CompletionCheck.Assessment(List.copyOf(items), confidence, "test-model"));
    }
}
