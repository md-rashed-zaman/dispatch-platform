package dev.rashed.dispatch.payment;

import io.swagger.v3.oas.annotations.Hidden;
import java.net.URI;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * What the provider and the customer's browser send us after a payment. Public (no token): the provider can't sign
 * in. So nothing here is believed: the fields only say which payment to check, and {@link PaymentService#confirm}
 * asks the provider itself.
 */
@Hidden
@RestController
@RequestMapping("/api/payments/sslcommerz")
class PaymentCallbacks {

    private static final Logger log = LoggerFactory.getLogger(PaymentCallbacks.class);
    private static final Set<String> PAID = Set.of("VALID", "VALIDATED");

    private final PaymentService payments;
    private final String webBaseUrl;

    PaymentCallbacks(PaymentService payments, @Value("${dispatch.web.base-url}") String webBaseUrl) {
        this.payments = payments;
        this.webBaseUrl = webBaseUrl.replaceAll("/$", "");
    }

    /**
     * The provider's notification (IPN), server to server. 200 once handled, including a notice we ignore (an
     * unknown or forged one: retrying it won't help); 503 when we couldn't ask the provider, so it sends it again.
     */
    @RequestMapping(path = "/ipn", method = RequestMethod.POST)
    ResponseEntity<String> ipn(
            @RequestParam(name = "tran_id", required = false) @Nullable String tranId,
            @RequestParam(name = "val_id", required = false) @Nullable String valId,
            @RequestParam(name = "status", required = false) @Nullable String status) {
        if (tranId == null || tranId.isBlank()) {
            return ResponseEntity.badRequest().body("tran_id is required");
        }
        try {
            payments.confirm(tranId, status != null && PAID.contains(status) ? valId : null);
            return ResponseEntity.ok("received");
        } catch (PaymentProvider.Unavailable | PaymentProvider.Refused cantCheck) {
            log.warn("Payment notice for {} not checked yet: {}", tranId, cantCheck.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("try again later");
        }
    }

    /**
     * The customer's browser coming back from the payment page (success, failure or cancel: the provider posts the
     * same fields to each). Confirms if it can, then sends the browser to the work order, which shows the payment's
     * state and polls while it's still open. A failed check here is fine: the page and the reconciler try again.
     */
    @RequestMapping(
            path = "/return",
            method = {RequestMethod.POST, RequestMethod.GET})
    ResponseEntity<Void> returned(
            @RequestParam(name = "tran_id", required = false) @Nullable String tranId,
            @RequestParam(name = "val_id", required = false) @Nullable String valId,
            @RequestParam(name = "status", required = false) @Nullable String status) {
        var target = webBaseUrl + "/work-orders";
        // What the browser says happened, only to word the page ("declined"); the payment's state comes from the
        // provider. Anything unexpected reads as a plain return, which makes the page check.
        var said = status == null
                ? "returned"
                : switch (status) {
                    case "FAILED" -> "declined";
                    case "CANCELLED" -> "cancelled";
                    default -> "returned";
                };
        if (tranId != null && !tranId.isBlank()) {
            try {
                var payment = payments.confirm(tranId, status != null && PAID.contains(status) ? valId : null);
                if (payment.isPresent()) {
                    target = webBaseUrl + "/work-orders/" + payment.get().workOrderId() + "?payment=" + said;
                }
            } catch (RuntimeException notYet) {
                log.warn("Payment return for {} not confirmed yet: {}", tranId, notYet.getMessage());
                target = payments.workOrderOf(tranId)
                        .map(id -> webBaseUrl + "/work-orders/" + id + "?payment=" + said)
                        .orElse(target);
            }
        }
        // 303: the browser follows with GET, whatever method brought it here.
        return ResponseEntity.status(HttpStatus.SEE_OTHER)
                .location(URI.create(target))
                .build();
    }
}
