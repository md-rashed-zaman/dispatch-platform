package dev.rashed.dispatch.payment;

import io.swagger.v3.oas.annotations.Hidden;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

/**
 * A stand-in for SSLCommerz, for local runs and demos without a sandbox store (dispatch.payments.sslcommerz.fake).
 * It speaks the same endpoints, fields and status words as {@link SslCommerz} expects, keeps everything in memory,
 * and its payment page has buttons instead of cards: pay, decline, cancel, and "pay but lose the notification" to
 * see the reconciler settle it. Never in production: anyone can "pay".
 */
@Hidden
@RestController
@RequestMapping("/fake-sslcommerz")
@ConditionalOnBooleanProperty("dispatch.payments.sslcommerz.fake")
class FakeSslCommerz {

    private static final Logger log = LoggerFactory.getLogger(FakeSslCommerz.class);
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private record Session(
            String key, String tranId, String amount, String currency, String product, Map<String, String> urls) {}

    private record Txn(
            String tranId,
            String valId,
            String bankTranId,
            String status,
            String amount,
            String currency,
            String when) {}

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, List<Txn>> byTranId = new ConcurrentHashMap<>();
    private final Map<String, Txn> byValId = new ConcurrentHashMap<>();
    private final Map<String, Boolean> validated = new ConcurrentHashMap<>();
    private final Map<String, String> refundsByTransId = new ConcurrentHashMap<>();
    private final Map<String, Integer> refundChecks = new ConcurrentHashMap<>();
    private final PaymentProperties.SslCommerz settings;
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    FakeSslCommerz(PaymentProperties properties) {
        this.settings = properties.sslcommerz();
    }

    @PostMapping(path = "/gwprocess/v4/api.php", produces = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> createSession(@RequestParam MultiValueMap<String, String> form) {
        if (!credentialsOk(form.getFirst("store_id"), form.getFirst("store_passwd"))) {
            return Map.of("status", "FAILED", "failedreason", "Store Credential Error Or Store is De-active");
        }
        var key = UUID.randomUUID().toString().replace("-", "").toUpperCase();
        var urls = new LinkedHashMap<String, String>();
        for (var name : List.of("success_url", "fail_url", "cancel_url", "ipn_url")) {
            urls.put(name, form.getFirst(name));
        }
        sessions.put(
                key,
                new Session(
                        key,
                        form.getFirst("tran_id"),
                        form.getFirst("total_amount"),
                        form.getFirst("currency"),
                        form.getFirst("product_name"),
                        urls));
        return Map.of(
                "status",
                "SUCCESS",
                "failedreason",
                "",
                "sessionkey",
                key,
                "GatewayPageURL",
                settings.baseUrl() + "/pay/" + key);
    }

    @GetMapping(path = "/pay/{key}", produces = MediaType.TEXT_HTML_VALUE)
    ResponseEntity<String> page(@PathVariable String key) {
        var session = sessions.get(key);
        if (session == null) {
            return ResponseEntity.status(404)
                    .body(html("Payment page expired", "<p>This payment page doesn't exist any more.</p>"));
        }
        var amount = new BigDecimal(session.amount()).setScale(2).toPlainString();
        var body = """
                <p class="label">Paying</p>
                <p class="amount">%s %s</p>
                <p class="product">%s</p>
                <form method="post" action="%s/pay/%s/pay"><button class="primary">Pay</button></form>
                <form method="post" action="%s/pay/%s/pay-quietly"><button>Pay, but lose the notification</button></form>
                <form method="post" action="%s/pay/%s/decline"><button>Card declined</button></form>
                <form method="post" action="%s/pay/%s/cancel"><button>Cancel</button></form>
                <p class="note">A stand-in for SSLCommerz: no money moves. Transaction %s.</p>
                """.formatted(
                        esc(session.currency()),
                        esc(amount),
                        esc(session.product()),
                        settings.baseUrl(),
                        key,
                        settings.baseUrl(),
                        key,
                        settings.baseUrl(),
                        key,
                        settings.baseUrl(),
                        key,
                        esc(session.tranId()));
        return ResponseEntity.ok(html("Pay with SSLCommerz (fake)", body));
    }

    @PostMapping(path = "/pay/{key}/{action}", produces = MediaType.TEXT_HTML_VALUE)
    ResponseEntity<String> act(@PathVariable String key, @PathVariable String action) {
        var session = sessions.get(key);
        if (session == null) {
            return ResponseEntity.status(404)
                    .body(html("Payment page expired", "<p>This payment page doesn't exist any more.</p>"));
        }
        var status = switch (action) {
            case "pay", "pay-quietly" -> "VALID";
            case "decline" -> "FAILED";
            default -> "CANCELLED";
        };
        var txn = new Txn(
                session.tranId(),
                UUID.randomUUID().toString().replace("-", ""),
                "FAKEBANK" + System.nanoTime(),
                status,
                session.amount(),
                session.currency(),
                LocalDateTime.now().format(WHEN));
        byTranId.computeIfAbsent(session.tranId(), id -> new ArrayList<>()).add(txn);
        byValId.put(txn.valId(), txn);

        var fields = new LinkedHashMap<String, String>();
        fields.put("tran_id", txn.tranId());
        fields.put("val_id", txn.valId());
        fields.put("status", status);
        fields.put("amount", txn.amount());
        fields.put("currency", txn.currency());
        if (!action.equals("pay-quietly")) {
            notify(session.urls().get("ipn_url"), fields);
        }
        var back = session.urls()
                .get(status.equals("VALID") ? "success_url" : status.equals("FAILED") ? "fail_url" : "cancel_url");
        // Like the real page: the browser posts the result to the shop's return URL.
        var inputs = fields.entrySet().stream()
                .map(f -> "<input type=\"hidden\" name=\"%s\" value=\"%s\">"
                        .formatted(esc(f.getKey()), esc(f.getValue())))
                .collect(Collectors.joining());
        return ResponseEntity.ok(html("Returning to the shop", """
                <p>Returning to the shop…</p>
                <form id="back" method="post" action="%s">%s<noscript><button>Continue</button></noscript></form>
                <script>document.getElementById('back').submit()</script>
                """.formatted(esc(back), inputs)));
    }

    @GetMapping(path = "/validator/api/validationserverAPI.php", produces = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> validate(
            @RequestParam("val_id") String valId,
            @RequestParam("store_id") String storeId,
            @RequestParam("store_passwd") String password) {
        var txn = byValId.get(valId);
        if (!credentialsOk(storeId, password) || txn == null || !txn.status().equals("VALID")) {
            return Map.of("status", "INVALID_TRANSACTION");
        }
        var again = validated.putIfAbsent(valId, true) != null;
        return describe(txn, again ? "VALIDATED" : "VALID");
    }

    /** One path, three questions, as at SSLCommerz: a transaction query, a refund, or a refund's status. */
    @GetMapping(path = "/validator/api/merchantTransIDvalidationAPI.php", produces = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> query(
            @RequestParam(name = "tran_id", required = false) String tranId,
            @RequestParam(name = "bank_tran_id", required = false) String bankTranId,
            @RequestParam(name = "refund_trans_id", required = false) String refundTransId,
            @RequestParam(name = "refund_ref_id", required = false) String refundRefId,
            @RequestParam("store_id") String storeId,
            @RequestParam("store_passwd") String password) {
        if (!credentialsOk(storeId, password)) {
            return Map.of("APIConnect", "FAILED");
        }
        if (bankTranId != null) {
            var paid = byValId.values().stream()
                    .anyMatch(
                            t -> t.bankTranId().equals(bankTranId) && t.status().equals("VALID"));
            if (!paid) {
                return Map.of("APIConnect", "DONE", "status", "failed", "errorReason", "Invalid bank transaction id");
            }
            // Asked again with the same refund_trans_id: "processing", same reference (as SSLCommerz).
            var existing = refundsByTransId.get(refundTransId);
            if (existing != null) {
                return Map.of(
                        "APIConnect",
                        "DONE",
                        "status",
                        "processing",
                        "refund_ref_id",
                        existing,
                        "bank_tran_id",
                        bankTranId);
            }
            var ref = "FAKEREF" + System.nanoTime();
            refundsByTransId.put(refundTransId, ref);
            refundChecks.put(ref, 0);
            return Map.of("APIConnect", "DONE", "status", "success", "refund_ref_id", ref, "bank_tran_id", bankTranId);
        }
        if (refundRefId != null) {
            // Processing the first time it's asked about, refunded after: enough to see both states.
            var checks = refundChecks.merge(refundRefId, 1, Integer::sum);
            return Map.of(
                    "APIConnect",
                    "DONE",
                    "refund_ref_id",
                    refundRefId,
                    "status",
                    checks > 1 ? "refunded" : "processing");
        }
        var found = byTranId.getOrDefault(tranId, List.of()).stream()
                .map(t -> describe(t, t.status().equals("VALID") ? "VALID" : "FAILED"))
                .toList();
        return Map.of("APIConnect", "DONE", "no_of_trans_found", found.size(), "element", found);
    }

    private Map<String, Object> describe(Txn t, String status) {
        var answer = new LinkedHashMap<String, Object>();
        answer.put("status", status);
        answer.put("tran_date", t.when());
        answer.put("tran_id", t.tranId());
        answer.put("val_id", t.valId());
        answer.put("amount", t.amount());
        answer.put("currency", t.currency());
        answer.put("currency_type", t.currency());
        answer.put("currency_amount", t.amount());
        // SSLCommerz pays out the amount minus its charge: 2.5% here, like its documentation's example.
        answer.put(
                "store_amount",
                new BigDecimal(t.amount())
                        .multiply(new BigDecimal("0.975"))
                        .setScale(2, java.math.RoundingMode.DOWN)
                        .toPlainString());
        answer.put("bank_tran_id", t.bankTranId());
        answer.put("risk_level", "0");
        answer.put("risk_title", "Safe");
        return answer;
    }

    /** The IPN, from a background thread after a moment: like the real one, it isn't part of the browser's request. */
    private void notify(String ipnUrl, Map<String, String> fields) {
        var form = fields.entrySet().stream()
                .map(f -> f.getKey() + "=" + java.net.URLEncoder.encode(f.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(300);
                var response = http.send(
                        HttpRequest.newBuilder(URI.create(ipnUrl))
                                .header("Content-Type", MediaType.APPLICATION_FORM_URLENCODED_VALUE)
                                .POST(HttpRequest.BodyPublishers.ofString(form))
                                .timeout(Duration.ofSeconds(10))
                                .build(),
                        HttpResponse.BodyHandlers.discarding());
                log.info("Fake SSLCommerz: IPN for {} answered {}", fields.get("tran_id"), response.statusCode());
            } catch (Exception e) {
                log.warn("Fake SSLCommerz: IPN for {} failed: {}", fields.get("tran_id"), e.toString());
            }
        });
    }

    private boolean credentialsOk(String storeId, String password) {
        return settings.storeId().equals(storeId) && settings.storePassword().equals(password);
    }

    private static String esc(String text) {
        return text == null ? "" : HtmlUtils.htmlEscape(text);
    }

    private static String html(String title, String body) {
        return """
                <!doctype html><html lang="en"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1"><title>%s</title>
                <style>
                  body{font-family:system-ui,sans-serif;background:#f1f5f9;color:#0f172a;display:grid;place-items:center;min-height:100vh;margin:0}
                  main{background:#fff;border:1px solid #e2e8f0;border-radius:10px;padding:24px;width:min(360px,calc(100vw - 32px))}
                  h1{font-size:16px;margin:0 0 16px}.label{margin:0;color:#475569;font-size:13px}
                  .amount{font-size:28px;font-weight:600;margin:4px 0}.product{margin:0 0 20px;color:#475569}
                  form{margin:8px 0}button{font:inherit;width:100%%;height:44px;border-radius:6px;border:1px solid #cbd5e1;background:#fff;font-size:15px;cursor:pointer}
                  button.primary{background:#0f766e;border-color:#0f766e;color:#fff}.note{font-size:12px;color:#64748b;margin-top:16px}
                </style></head><body><main><h1>%s</h1>%s</main></body></html>
                """.formatted(esc(title), esc(title), body);
    }
}
