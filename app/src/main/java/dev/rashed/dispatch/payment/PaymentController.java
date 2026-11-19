package dev.rashed.dispatch.payment;

import dev.rashed.dispatch.shared.NotFoundException;
import dev.rashed.dispatch.tenancy.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

@Tag(name = "Payments", description = "Funding a work order through the payment provider's checkout page")
@RestController
@PreAuthorize("hasAnyRole('CLIENT_ADMIN', 'DISPATCHER')")
class PaymentController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final PaymentService payments;
    private final IdempotencyKeys keys;
    private final JsonMapper json;

    PaymentController(PaymentService payments, IdempotencyKeys keys, JsonMapper json) {
        this.payments = payments;
        this.keys = keys;
        this.json = json;
    }

    /**
     * Starts funding (or resumes it): the answer's {@code redirectUrl} is the provider's payment page. Requires an
     * {@code Idempotency-Key}: a retry with the same key gets the first answer, never a second payment.
     */
    @Operation(summary = "Fund an assigned work order: returns the payment page to send the client to")
    @PostMapping("/api/work-orders/{id}/payments")
    ResponseEntity<?> start(
            @PathVariable UUID id,
            @Parameter(description = "A unique value per payment attempt (a UUID); the same value on every retry")
                    @RequestHeader(name = IDEMPOTENCY_KEY, required = false)
                    String key) {
        if (key == null || key.isBlank() || key.length() > 255) {
            return problem(
                    HttpStatus.BAD_REQUEST,
                    "idempotency-key-required",
                    "An Idempotency-Key header (1 to 255 characters, a UUID is ideal) is required to start a payment.");
        }
        var tenant = TenantContext.current().orElseThrow(() -> new NotFoundException("Work order", id));
        // The request is its method and path: this endpoint takes no body.
        var scope = "POST /api/work-orders/" + id + "/payments";
        var claim = keys.claim(tenant, key, scope, sha256(scope));
        return switch (claim) {
            case IdempotencyKeys.Claim.Replay replay ->
                ResponseEntity.status(replay.status())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotent-Replayed", "true")
                        .body(replay.body());
            case IdempotencyKeys.Claim.InProgress ignored ->
                ResponseEntity.status(HttpStatus.CONFLICT)
                        .header(HttpHeaders.RETRY_AFTER, "1")
                        .body(problemBody(
                                HttpStatus.CONFLICT,
                                "idempotency-in-progress",
                                "A request with this Idempotency-Key is still running. Try again in a moment."));
            case IdempotencyKeys.Claim.Mismatch ignored ->
                problem(
                        HttpStatus.UNPROCESSABLE_CONTENT,
                        "idempotency-key-reused",
                        "This Idempotency-Key was already used for a different request. Use a new key for a new request.");
            case IdempotencyKeys.Claim.Claimed ignored -> {
                try {
                    var view = payments.start(id);
                    var body = json.writeValueAsString(view);
                    keys.complete(tenant, key, HttpStatus.OK.value(), body);
                    yield ResponseEntity.ok()
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(body);
                } catch (RuntimeException failed) {
                    keys.release(tenant, key); // errors aren't stored: a retry runs again
                    throw failed;
                }
            }
        };
    }

    @Operation(summary = "The work order's latest payment: poll it after the client comes back from the payment page")
    @GetMapping("/api/work-orders/{id}/payment")
    PaymentService.PaymentView latest(@PathVariable UUID id) {
        return payments.latest(id).orElseThrow(() -> new NotFoundException("Payment for work order", id));
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String type, String detail) {
        return ResponseEntity.status(status).body(problemBody(status, type, detail));
    }

    private static ProblemDetail problemBody(HttpStatus status, String type, String detail) {
        var problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("urn:dispatch:problem:" + type));
        problem.setTitle(status.getReasonPhrase());
        return problem;
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
