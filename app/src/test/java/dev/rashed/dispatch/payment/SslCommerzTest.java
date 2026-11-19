package dev.rashed.dispatch.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import dev.rashed.dispatch.shared.Money;
import io.micrometer.observation.ObservationRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The SSLCommerz client against a local server answering like SSLCommerz (bodies shaped like the examples in its
 * v4 reference): what we send, how answers are read, and which failures mean "unknown" and which mean "no".
 */
class SslCommerzTest {

    HttpServer server;
    final Map<String, String> answers = new ConcurrentHashMap<>();
    final Map<String, String> received = new ConcurrentHashMap<>();
    volatile int status = 200;
    volatile long delayMillis;
    SslCommerz sslcommerz;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            var path = exchange.getRequestURI().getPath();
            received.put(
                    path,
                    exchange.getRequestURI().getRawQuery() == null
                            ? new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)
                            : exchange.getRequestURI().getRawQuery());
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            var body = answers.getOrDefault(path, "{}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        var properties = new PaymentProperties(
                true,
                URI.create("http://localhost:8080"),
                Duration.ofMinutes(1),
                Duration.ofMinutes(15),
                Duration.ofHours(2),
                1000,
                new PaymentProperties.SslCommerz(
                        URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                        "testbox",
                        "qwerty",
                        Duration.ofSeconds(1),
                        Duration.ofMillis(500),
                        false));
        sslcommerz = new SslCommerz(
                properties, ObservationRegistry.NOOP, JsonMapper.builder().build());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void createsASessionWithTheAmountAsADecimal() {
        answers.put("/gwprocess/v4/api.php", """
                {"status":"SUCCESS","failedreason":"","sessionkey":"F298BC45B0688E02768900C4F6B28C8B",
                 "GatewayPageURL":"https://sandbox.sslcommerz.com/EasyCheckOut/testcdef298bc45b0688e0"}""");

        var session = sslcommerz.createSession(checkout("3h9i9sjtopand8694yticnxt", Money.bdt(250_050)));

        assertThat(session.sessionKey()).isEqualTo("F298BC45B0688E02768900C4F6B28C8B");
        assertThat(session.gatewayUrl()).hasHost("sandbox.sslcommerz.com");
        assertThat(received.get("/gwprocess/v4/api.php"))
                .contains(
                        "total_amount=2500.50", "currency=BDT", "tran_id=3h9i9sjtopand8694yticnxt", "store_id=testbox")
                .contains("ipn_url=http%3A%2F%2Flocalhost%3A8080%2Fapi%2Fpayments%2Fsslcommerz%2Fipn");
    }

    @Test
    void aRefusedSessionIsANoNotAnUnknown() {
        answers.put("/gwprocess/v4/api.php", """
                {"status":"FAILED","failedreason":"Store Credential Error Or Store is De-active"}""");

        assertThatThrownBy(() -> sslcommerz.createSession(checkout("t1", Money.bdt(100))))
                .isInstanceOf(PaymentProvider.Refused.class)
                .hasMessageContaining("Store Credential Error");
    }

    @Test
    void readsAValidationAndKnowsAnUnknownId() {
        answers.put("/validator/api/validationserverAPI.php", """
                {"status":"VALID","tran_date":"2026-11-19 10:00:01","tran_id":"t1","val_id":"1711231900331kHP17lnrr9T8Gt",
                 "amount":"2500.50","store_amount":"2438.00","currency":"BDT","bank_tran_id":"1711231900331S0R8atkhAZksmM",
                 "card_type":"BKASH-BKash","currency_type":"BDT","currency_amount":"2500.50","risk_level":"0","risk_title":"Safe"}""");

        var t = sslcommerz.validate("1711231900331kHP17lnrr9T8Gt").orElseThrow();

        assertThat(t.status()).isEqualTo(PaymentProvider.Status.PAID);
        assertThat(t.amount()).isEqualTo(Money.bdt(250_050));
        assertThat(t.bankTranId()).isEqualTo("1711231900331S0R8atkhAZksmM");
        assertThat(received.get("/validator/api/validationserverAPI.php"))
                .contains("val_id=1711231900331kHP17lnrr9T8Gt");

        answers.put("/validator/api/validationserverAPI.php", "{\"status\":\"INVALID_TRANSACTION\"}");
        assertThat(sslcommerz.validate("forged")).isEmpty();
    }

    @Test
    void aQueryListsEveryAttemptInTheSession() {
        answers.put("/validator/api/merchantTransIDvalidationAPI.php", """
                {"APIConnect":"DONE","no_of_trans_found":2,"element":[
                  {"val_id":"","status":"FAILED","tran_id":"t1","amount":"100.00","currency_type":"BDT","currency_amount":"100.00","risk_level":"0"},
                  {"val_id":"V2","status":"VALIDATED","tran_id":"t1","amount":"100.00","currency_type":"BDT","currency_amount":"100.00","bank_tran_id":"B2","risk_level":"0"}]}""");

        var found = sslcommerz.query("t1");

        assertThat(found)
                .extracting(PaymentProvider.Transaction::status)
                .containsExactly(PaymentProvider.Status.FAILED, PaymentProvider.Status.PAID);
        assertThat(found.getLast().amount()).isEqualTo(Money.bdt(10_000));
    }

    @Test
    void timeoutsAndServerErrorsAreUnknownNotFailed() {
        delayMillis = 1_000; // past the 500 ms read timeout
        assertThatThrownBy(() -> sslcommerz.query("t1")).isInstanceOf(PaymentProvider.Unavailable.class);

        delayMillis = 0;
        status = 503;
        assertThatThrownBy(() -> sslcommerz.validate("v1")).isInstanceOf(PaymentProvider.Unavailable.class);

        status = 200;
        answers.put("/validator/api/validationserverAPI.php", "<html>maintenance</html>");
        assertThatThrownBy(() -> sslcommerz.validate("v1")).isInstanceOf(PaymentProvider.Unavailable.class);
    }

    private static PaymentProvider.Checkout checkout(String tranId, Money amount) {
        var base = "http://localhost:8080/api/payments/sslcommerz";
        return new PaymentProvider.Checkout(
                tranId,
                amount,
                "Mount a CCTV camera",
                "Meghna Bank",
                "ops@meghna.example",
                URI.create(base + "/return"),
                URI.create(base + "/return"),
                URI.create(base + "/return"),
                URI.create(base + "/ipn"));
    }
}
