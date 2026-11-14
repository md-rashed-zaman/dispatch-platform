package dev.rashed.dispatch.workorder;

import dev.rashed.dispatch.shared.CursorPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lifecycle changes are actions ({@code POST /{id}/publish}), never {@code PATCH {"status": ...}}: each
 * calls exactly one transition method, so the client can't pick a status and skip the rules.
 */
@Tag(name = "Work orders", description = "Your company's work orders")
@RestController
@RequestMapping("/api/work-orders")
class WorkOrderController {

    private static final Pattern STRONG_ETAG = Pattern.compile("\"(\\d+)\"");

    private final WorkOrderService workOrders;

    WorkOrderController(WorkOrderService workOrders) {
        this.workOrders = workOrders;
    }

    @Operation(summary = "Create a draft work order")
    @PostMapping
    ResponseEntity<WorkOrderView> create(@Valid @RequestBody CreateWorkOrderRequest request) {
        var created = workOrders.create(request);
        return ResponseEntity.created(URI.create("/api/work-orders/" + created.id()))
                .eTag(String.valueOf(created.version()))
                .body(created);
    }

    @Operation(
            summary = "Your company's work orders, newest first",
            description = "Pass \"next\" from the response as \"after\" to get the next page.")
    @GetMapping
    CursorPage<WorkOrderSummary> list(
            @RequestParam(required = false) WorkOrderStatus status,
            @Parameter(description = "Page size, 1 to 100") @RequestParam(defaultValue = "50") @Min(1) @Max(100)
                    int limit,
            @Parameter(description = "The \"next\" value from the previous page; leave out for the first page")
                    @RequestParam(required = false)
                    String after) {
        return workOrders.list(status, limit, after);
    }

    // Another tenant's work order is a 404, not a 403: a 403 would confirm that the id exists.
    @Operation(summary = "One of your company's work orders; the ETag header is its version")
    @GetMapping("/{id}")
    ResponseEntity<WorkOrderView> get(@PathVariable UUID id) {
        return withETag(workOrders.find(id));
    }

    @Operation(
            summary = "Edit a draft (only the fields you send)",
            description = "Requires If-Match with the ETag from your last read. 412 if someone changed it since.")
    @PatchMapping("/{id}")
    ResponseEntity<WorkOrderView> revise(
            @PathVariable UUID id,
            @Parameter(description = "ETag from your last read, like \"3\"")
                    @RequestHeader(value = HttpHeaders.IF_MATCH, required = false)
                    String ifMatch,
            @Valid @RequestBody ReviseWorkOrderRequest request) {
        return withETag(workOrders.revise(id, expectedVersion(ifMatch), request.toChanges()));
    }

    @Operation(summary = "Publish a draft so matching can find technicians")
    @PostMapping("/{id}/publish")
    ResponseEntity<WorkOrderView> publish(@PathVariable UUID id) {
        return withETag(workOrders.publish(id));
    }

    @Operation(summary = "Cancel (only before work has started)")
    @PostMapping("/{id}/cancel")
    ResponseEntity<WorkOrderView> cancel(@PathVariable UUID id) {
        return withETag(workOrders.cancel(id));
    }

    @Operation(summary = "Approve submitted work, so the technician gets paid")
    @PostMapping("/{id}/approve")
    ResponseEntity<WorkOrderView> approve(@PathVariable UUID id) {
        return withETag(workOrders.approve(id));
    }

    @Operation(summary = "Send submitted work back to the technician with a reason")
    @PostMapping("/{id}/reject")
    ResponseEntity<WorkOrderView> reject(@PathVariable UUID id, @Valid @RequestBody RejectWorkOrderRequest request) {
        return withETag(workOrders.reject(id, request.reason()));
    }

    private static ResponseEntity<WorkOrderView> withETag(WorkOrderView view) {
        return ResponseEntity.ok().eTag(String.valueOf(view.version())).body(view);
    }

    private static long expectedVersion(String ifMatch) {
        if (ifMatch == null) {
            throw new ErrorResponseException(
                    HttpStatus.PRECONDITION_REQUIRED,
                    ProblemDetail.forStatusAndDetail(
                            HttpStatus.PRECONDITION_REQUIRED,
                            "Send If-Match with the ETag from your last read, so you can't overwrite changes"
                                    + " you haven't seen."),
                    null);
        }
        var matcher = STRONG_ETAG.matcher(ifMatch.strip());
        if (!matcher.matches()) {
            throw new ErrorResponseException(
                    HttpStatus.PRECONDITION_FAILED,
                    ProblemDetail.forStatusAndDetail(
                            HttpStatus.PRECONDITION_FAILED, "If-Match must be an ETag from this API, like \"3\"."),
                    null);
        }
        return Long.parseLong(matcher.group(1));
    }
}
