package dev.rashed.dispatch.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.rashed.dispatch.TestTechnicians;
import dev.rashed.dispatch.TestTokens;
import dev.rashed.dispatch.TestcontainersConfiguration;
import dev.rashed.dispatch.shared.Money;
import dev.rashed.dispatch.tenancy.TenantContext;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Funding a work order end to end through the API, against a scripted provider: one payment however many times a
 * request is retried, capture only by the provider's own answer, and every way a notice can arrive (twice, forged,
 * late, never).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
        properties = {
            "dispatch.payments.required=true",
            "dispatch.payments.reconcile-initial-delay=PT1H",
            "dispatch.payments.reconcile-lock-at-least-for=PT0S" // the test runs it twice in a row
        })
@AutoConfigureMockMvc
class PaymentFlowTests {

    static final Money BUDGET = Money.bdt(250_000);
    static final URI PAGE = URI.create("https://sandbox.sslcommerz.com/EasyCheckOut/testcde123");

    @MockitoBean
    PaymentProvider provider;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate transaction;

    @Autowired
    PaymentReconciler reconciler;

    final UUID tenant = UUID.randomUUID();
    UUID technicianId;
    UUID workOrder;
    String dispatcher;
    String technician;

    @BeforeEach
    void setUp() {
        jdbc.update("insert into company (id, name, slug) values (?, 'Company', ?)", tenant, "company-" + tenant);
        dispatcher =
                TestTokens.token(c -> c.claim("roles", List.of("DISPATCHER")).claim("tenant_id", tenant.toString()));
        technicianId = TestTechnicians.verified(jdbc, 23.79, 90.41, "cctv");
        technician = TestTokens.token(
                c -> c.claim("roles", List.of("TECHNICIAN")).claim("technician_id", technicianId.toString()));
        workOrder = assignedWorkOrder();
        given(provider.createSession(any())).willReturn(new PaymentProvider.Session("SESSION1", PAGE));
    }

    @Test
    void aValidatedNoticeFundsTheJobOnceAndLetsTheTechnicianStart() throws Exception {
        mvc.perform(post("/api/work-orders/{id}/start", workOrder)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + technician))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:dispatch:problem:not-funded"));

        var started = start("key-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.redirectUrl").value(PAGE.toString()))
                .andExpect(jsonPath("$.amount.minor").value(250_000));
        var checkout = ArgumentCaptor.forClass(PaymentProvider.Checkout.class);
        verify(provider).createSession(checkout.capture());
        assertThat(checkout.getValue().amount()).isEqualTo(BUDGET);
        assertThat(checkout.getValue().ipnUrl().toString()).endsWith("/api/payments/sslcommerz/ipn");
        var tranId = checkout.getValue().tranId();
        assertThat(tranId).hasSizeLessThanOrEqualTo(30);

        given(provider.validate("VAL1")).willReturn(Optional.of(paid(tranId, BUDGET, 0)));
        for (int i = 0; i < 3; i++) { // the provider repeats notices; the browser returns too
            ipn(tranId, "VAL1", "VALID");
        }
        mvc.perform(post("/api/payments/sslcommerz/return")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("tran_id", tranId)
                        .param("val_id", "VAL1")
                        .param("status", "VALID"))
                .andExpect(status().isSeeOther())
                .andExpect(header().string(
                                HttpHeaders.LOCATION,
                                "http://localhost:5173/work-orders/" + workOrder + "?payment=returned"));

        verify(provider, times(1)).validate("VAL1"); // settled after the first: the rest change nothing
        assertThat(payment()).containsEntry("status", "CAPTURED").containsEntry("val_id", "VAL-" + tranId);
        assertThat(count("select count(*) from payment where work_order_id = ?", workOrder))
                .isEqualTo(1);
        assertThat(asTenant(() -> jdbc.queryForObject(
                        "select funded_at is not null from work_order where id = ?", Boolean.class, workOrder)))
                .isTrue();

        mvc.perform(get("/api/work-orders/{id}/payment", workOrder)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + dispatcher))
                .andExpect(jsonPath("$.status").value("CAPTURED"))
                .andExpect(jsonPath("$.redirectUrl").doesNotExist());
        mvc.perform(post("/api/work-orders/{id}/start", workOrder)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + technician))
                .andExpect(status().isOk());
        start("key-2")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:dispatch:problem:already-funded"));
    }

    @Test
    void fiveConcurrentRequestsWithTheSameKeyMakeOnePayment() throws Exception {
        given(provider.createSession(any())).willAnswer(call -> {
            Thread.sleep(300); // the provider takes a moment: the others arrive meanwhile
            return new PaymentProvider.Session("SESSION1", PAGE);
        });
        var ready = new CountDownLatch(1);
        var results = new ArrayList<Future<MvcResult>>();
        try (var pool = Executors.newFixedThreadPool(5)) {
            for (int i = 0; i < 5; i++) {
                results.add(pool.submit(() -> {
                    ready.await();
                    return start("same-key").andReturn();
                }));
            }
            ready.countDown();
        }
        var statuses = new ArrayList<Integer>();
        for (var result : results) {
            statuses.add(result.get().getResponse().getStatus());
        }

        assertThat(statuses).contains(200).allMatch(s -> s == 200 || s == 409);
        verify(provider, times(1)).createSession(any());
        assertThat(count("select count(*) from payment where work_order_id = ?", workOrder))
                .isEqualTo(1);
        // Once the first is done, a retry gets its answer back.
        start("same-key")
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.redirectUrl").value(PAGE.toString()));
    }

    @Test
    void differentKeysForTheSameJobStillMakeOnePayment() throws Exception {
        start("click-1").andExpect(status().isOk());
        start("click-2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.redirectUrl").value(PAGE.toString()));

        verify(provider, times(1)).createSession(any());
        assertThat(count("select count(*) from payment where work_order_id = ?", workOrder))
                .isEqualTo(1);
    }

    @Test
    void aKeyReusedForAnotherRequestIsRejected() throws Exception {
        start("key-1").andExpect(status().isOk());
        var other = assignedWorkOrder();

        mvc.perform(post("/api/work-orders/{id}/payments", other)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + dispatcher)
                        .header("Idempotency-Key", "key-1"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("urn:dispatch:problem:idempotency-key-reused"));
        mvc.perform(post("/api/work-orders/{id}/payments", other)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + dispatcher))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:dispatch:problem:idempotency-key-required"));
    }

    @Test
    void aForgedNoticeChangesNothing() throws Exception {
        var tranId = startAndTranId();
        given(provider.validate("MADE-UP")).willReturn(Optional.empty());

        ipn(tranId, "MADE-UP", "VALID");
        ipn("no-such-payment", "MADE-UP", "VALID");

        assertThat(payment()).containsEntry("status", "PENDING");
        verify(provider, never()).query(anyString());
    }

    @Test
    void aPaymentForAnotherAmountIsNeverACapture() throws Exception {
        var tranId = startAndTranId();
        given(provider.validate("SMALL")).willReturn(Optional.of(paid(tranId, Money.bdt(1_000), 0)));
        given(provider.validate("OTHER")).willReturn(Optional.of(paid("someone-elses", BUDGET, 0)));

        ipn(tranId, "SMALL", "VALID");
        ipn(tranId, "OTHER", "VALID");

        assertThat(payment()).containsEntry("status", "PENDING");
        assertThat(mismatches()).containsExactlyInAnyOrder("VALID", "OTHER_TRAN_ID");
    }

    @Test
    void aRiskyPaymentWaitsForAPerson() throws Exception {
        var tranId = startAndTranId();
        given(provider.validate("RISKY")).willReturn(Optional.of(paid(tranId, BUDGET, 1)));

        ipn(tranId, "RISKY", "VALID");

        assertThat(payment()).containsEntry("status", "PENDING").containsEntry("reason", "Risk review");
        assertThat(mismatches()).containsExactly("VALID");
    }

    @Test
    void whenThePaymentPageCantBeCreatedARetryStartsAFreshAttempt() throws Exception {
        given(provider.createSession(any()))
                .willThrow(new PaymentProvider.Unavailable("read timed out", null))
                .willReturn(new PaymentProvider.Session("SESSION2", PAGE));

        start("key-1")
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "5"));
        start("key-1").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING"));

        // The first never had a page, so nobody could pay it: closed, and the retry is a new transaction.
        assertThat(asTenant(() -> jdbc.queryForList(
                        "select status from payment where work_order_id = ? order by created_at",
                        String.class,
                        workOrder)))
                .containsExactly("FAILED", "PENDING");
    }

    @Test
    void theReconcilerSettlesWhatTheNoticesMissed() throws Exception {
        var tranId = startAndTranId();
        age("30 minutes");
        given(provider.query(tranId)).willReturn(List.of(failed(tranId), paid(tranId, BUDGET, 0)));

        reconciler.settleOpen();

        assertThat(payment()).containsEntry("status", "CAPTURED");
    }

    @Test
    void anUnpaidPageExpiresAndAPaymentAfterThatIsFlaggedForARefund() throws Exception {
        var tranId = startAndTranId();
        given(provider.query(tranId)).willReturn(List.of());
        age("30 minutes");
        reconciler.settleOpen();
        assertThat(payment()).containsEntry("status", "PENDING"); // the page may still be open: wait

        age("3 hours");
        reconciler.settleOpen();
        assertThat(payment()).containsEntry("status", "EXPIRED");

        given(provider.validate("LATE")).willReturn(Optional.of(paid(tranId, BUDGET, 0)));
        ipn(tranId, "LATE", "VALID");
        assertThat(payment()).containsEntry("status", "EXPIRED");
        assertThat(mismatches()).containsExactly("VALID");

        start("key-2").andExpect(status().isOk()); // a new attempt is allowed
        assertThat(count("select count(*) from payment where work_order_id = ?", workOrder))
                .isEqualTo(2);
    }

    @Test
    void onlyTheCompanysStaffCanFundItsJobs() throws Exception {
        var other = UUID.randomUUID();
        jdbc.update("insert into company (id, name, slug) values (?, 'Other', ?)", other, "company-" + other);
        var otherDispatcher =
                TestTokens.token(c -> c.claim("roles", List.of("DISPATCHER")).claim("tenant_id", other.toString()));

        mvc.perform(post("/api/work-orders/{id}/payments", workOrder)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherDispatcher)
                        .header("Idempotency-Key", "k"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/work-orders/{id}/payments", workOrder)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + technician)
                        .header("Idempotency-Key", "k"))
                .andExpect(status().isForbidden());
        verify(provider, never()).createSession(any());
    }

    private org.springframework.test.web.servlet.ResultActions start(String key) throws Exception {
        return mvc.perform(post("/api/work-orders/{id}/payments", workOrder)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + dispatcher)
                .header("Idempotency-Key", key));
    }

    private String startAndTranId() throws Exception {
        start("key-1").andExpect(status().isOk());
        return payment().get("tran_id").toString();
    }

    private void ipn(String tranId, String valId, String providerStatus) throws Exception {
        mvc.perform(post("/api/payments/sslcommerz/ipn")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("tran_id", tranId)
                        .param("val_id", valId)
                        .param("status", providerStatus))
                .andExpect(status().isOk());
    }

    private static PaymentProvider.Transaction paid(String tranId, Money amount, int risk) {
        return new PaymentProvider.Transaction(
                tranId, "VAL-" + tranId, PaymentProvider.Status.PAID, amount, "BANK1", risk, "VALID");
    }

    private static PaymentProvider.Transaction failed(String tranId) {
        return new PaymentProvider.Transaction(tranId, null, PaymentProvider.Status.FAILED, BUDGET, null, 0, "FAILED");
    }

    private void age(String interval) {
        asTenant(() -> jdbc.update(
                "update payment set created_at = now() - cast(? as interval) where work_order_id = ?",
                interval,
                workOrder));
    }

    private Map<String, Object> payment() {
        return asTenant(() -> jdbc.queryForMap(
                "select * from payment where work_order_id = ? order by created_at desc limit 1", workOrder));
    }

    private List<String> mismatches() {
        return asTenant(() -> jdbc.queryForList(
                "select m.provider_status from payment_mismatch m join payment p on p.id = m.payment_id where p.work_order_id = ?",
                String.class,
                workOrder));
    }

    private int count(String sql, Object... args) {
        return asTenant(() -> jdbc.queryForObject(sql, Integer.class, args));
    }

    private UUID assignedWorkOrder() {
        var id = UUID.randomUUID();
        asTenant(() -> jdbc.update("""
                insert into work_order (id, tenant_id, title, description, status, site_address, site_latitude,
                    site_longitude, required_skills, window_starts_at, window_ends_at, budget_minor,
                    budget_currency, technician_id)
                values (?, ?, 'Mount a CCTV camera', 'On the outer wall', 'ASSIGNED', 'Gulshan, Dhaka', 23.79,
                    90.41, '{cctv}', now(), now() + interval '4 hours', 250000, 'BDT', ?)
                """, id, tenant, technicianId));
        return id;
    }

    private <T> T asTenant(Supplier<T> work) {
        return TenantContext.callAs(tenant, () -> transaction.execute(s -> work.get()));
    }
}
