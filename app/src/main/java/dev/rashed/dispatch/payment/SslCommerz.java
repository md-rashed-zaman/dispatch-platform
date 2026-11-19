package dev.rashed.dispatch.payment;

import dev.rashed.dispatch.shared.Money;
import io.micrometer.observation.ObservationRegistry;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * SSLCommerz v4 (sandbox or live, by base URL). Field names and status words are from its API reference
 * (developer.sslcommerz.com, checked in step 4.1):
 *
 * <ul>
 *   <li>create a session: {@code POST /gwprocess/v4/api.php}, answer {@code status=SUCCESS}, {@code sessionkey},
 *       {@code GatewayPageURL};
 *   <li>validate a notice: {@code GET /validator/api/validationserverAPI.php?val_id=}, answer {@code VALID},
 *       {@code VALIDATED} or {@code INVALID_TRANSACTION};
 *   <li>query by our id: {@code GET /validator/api/merchantTransIDvalidationAPI.php?tran_id=}, answer
 *       {@code APIConnect=DONE} and an {@code element} list (VALID, VALIDATED, PENDING, FAILED).
 * </ul>
 */
@Component
class SslCommerz implements PaymentProvider {

    private static final Logger log = LoggerFactory.getLogger(SslCommerz.class);

    private final RestClient http;
    private final PaymentProperties.SslCommerz settings;
    private final JsonMapper json;

    SslCommerz(PaymentProperties properties, ObservationRegistry observations, JsonMapper json) {
        this.settings = properties.sslcommerz();
        this.json = json;
        var client = HttpClient.newBuilder()
                .connectTimeout(settings.connectTimeout())
                .build();
        var requests = new JdkClientHttpRequestFactory(client);
        requests.setReadTimeout(settings.readTimeout());
        // Observed like incoming requests: each provider call is a span in the payment's trace.
        this.http = RestClient.builder()
                .observationRegistry(observations)
                .baseUrl(settings.baseUrl().toString())
                .requestFactory(requests)
                .build();
    }

    @Override
    public Session createSession(Checkout c) {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("store_id", settings.storeId());
        form.add("store_passwd", settings.storePassword());
        form.add("total_amount", decimal(c.amount()));
        form.add("currency", c.amount().currency());
        form.add("tran_id", c.tranId());
        form.add("success_url", c.successUrl().toString());
        form.add("fail_url", c.failUrl().toString());
        form.add("cancel_url", c.cancelUrl().toString());
        form.add("ipn_url", c.ipnUrl().toString());
        form.add("emi_option", "0");
        form.add("product_name", c.productName());
        form.add("product_category", "Field service");
        form.add("product_profile", "non-physical-goods");
        form.add("shipping_method", "NO");
        form.add("num_of_item", "1");
        form.add("cus_name", c.customerName());
        form.add("cus_email", c.customerEmail());
        // shortcut: Dispatch doesn't collect a client's billing address or phone, and SSLCommerz requires the
        // fields; they carry fixed values. Collect billing details when clients need them on receipts.
        form.add("cus_add1", "Dhaka");
        form.add("cus_city", "Dhaka");
        form.add("cus_postcode", "1000");
        form.add("cus_country", "Bangladesh");
        form.add("cus_phone", "N/A");

        var answer = call(() -> http.post()
                .uri("/gwprocess/v4/api.php")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(String.class));
        if (!"SUCCESS".equals(text(answer, "status"))) {
            throw new Refused("session refused: " + text(answer, "failedreason"));
        }
        var sessionKey = text(answer, "sessionkey");
        var page = text(answer, "GatewayPageURL");
        if (sessionKey == null || page == null) {
            throw new Unavailable("session answer without a session key or page URL", null);
        }
        return new Session(sessionKey, URI.create(page));
    }

    @Override
    public Optional<Transaction> validate(String valId) {
        var answer = call(() -> http.get()
                .uri(uri -> credentials(uri.path("/validator/api/validationserverAPI.php"))
                        .queryParam("val_id", valId)
                        .build())
                .retrieve()
                .body(String.class));
        if ("INVALID_TRANSACTION".equals(text(answer, "status"))) {
            return Optional.empty();
        }
        return Optional.of(transaction(answer));
    }

    @Override
    public List<Transaction> query(String tranId) {
        var answer = call(() -> http.get()
                .uri(uri -> credentials(uri.path("/validator/api/merchantTransIDvalidationAPI.php"))
                        .queryParam("tran_id", tranId)
                        .build())
                .retrieve()
                .body(String.class));
        var connect = text(answer, "APIConnect");
        if (!"DONE".equals(connect)) {
            throw new Refused("query refused: APIConnect " + connect);
        }
        var found = new ArrayList<Transaction>();
        var elements = answer.path("element");
        for (var element : elements) {
            found.add(transaction(element));
        }
        return found;
    }

    private UriBuilder credentials(UriBuilder uri) {
        return uri.queryParam("store_id", settings.storeId())
                .queryParam("store_passwd", settings.storePassword())
                .queryParam("format", "json");
    }

    /** One call; any doubt about what happened at the provider is {@link Unavailable}, a clear no is {@link Refused}. */
    private JsonNode call(java.util.function.Supplier<String> request) {
        String body;
        try {
            body = request.get();
        } catch (ResourceAccessException timeoutOrConnection) {
            throw new Unavailable("SSLCommerz unreachable: " + timeoutOrConnection.getMessage(), timeoutOrConnection);
        } catch (RestClientResponseException status) {
            if (status.getStatusCode().is5xxServerError()) {
                throw new Unavailable(
                        "SSLCommerz answered " + status.getStatusCode().value(), status);
            }
            throw new Refused("SSLCommerz answered " + status.getStatusCode().value());
        }
        try {
            return json.readTree(body == null ? "" : body);
        } catch (RuntimeException unreadable) {
            log.warn("SSLCommerz answer isn't JSON: {}", abbreviate(body));
            throw new Unavailable("unreadable answer from SSLCommerz", unreadable);
        }
    }

    /**
     * The amount the customer was asked for is {@code currency_amount} in {@code currency_type} (what we sent);
     * {@code amount} may be converted to BDT. Older answers only carry {@code amount} and {@code currency}.
     */
    private static Transaction transaction(JsonNode t) {
        var currency = firstText(t, "currency_type", "currency");
        var amount = firstText(t, "currency_amount", "amount");
        var tranId = text(t, "tran_id");
        var providerStatus = text(t, "status");
        if (currency == null || amount == null || tranId == null || providerStatus == null) {
            throw new Unavailable("transaction answer without tran_id, status, amount or currency", null);
        }
        Money money;
        try {
            money = new Money(new BigDecimal(amount).movePointRight(2).longValueExact(), currency);
        } catch (RuntimeException badAmount) {
            throw new Unavailable("unreadable amount " + amount + " " + currency, badAmount);
        }
        var risk = text(t, "risk_level");
        return new Transaction(
                tranId,
                text(t, "val_id"),
                status(providerStatus),
                money,
                text(t, "bank_tran_id"),
                risk == null || risk.isBlank() ? 0 : Integer.parseInt(risk.trim()),
                providerStatus);
    }

    private static Status status(String word) {
        return switch (word) {
            case "VALID", "VALIDATED" -> Status.PAID;
            case "PENDING" -> Status.PENDING;
            case "FAILED", "CANCELLED", "UNATTEMPTED", "EXPIRED" -> Status.FAILED;
            default -> throw new Unavailable("unknown SSLCommerz status " + word, null);
        };
    }

    /** BDT has two decimals: 500000 poisha is "5000.00". */
    static String decimal(Money money) {
        return BigDecimal.valueOf(money.minor(), 2).toPlainString();
    }

    private static @Nullable String text(JsonNode node, String field) {
        var value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asString();
    }

    private static @Nullable String firstText(JsonNode node, String first, String second) {
        var value = text(node, first);
        return value == null || value.isBlank() ? text(node, second) : value;
    }

    private static String abbreviate(@Nullable String body) {
        return body == null ? "(empty)" : body.length() > 200 ? body.substring(0, 200) + "…" : body;
    }
}
