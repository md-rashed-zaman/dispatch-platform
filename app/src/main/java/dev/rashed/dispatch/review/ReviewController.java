package dev.rashed.dispatch.review;

import dev.rashed.dispatch.workorder.RejectWorkOrderRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Review", description = "Finished work the AI check couldn't approve on its own")
@RestController
class ReviewController {

    private final ReviewService reviews;

    ReviewController(ReviewService reviews) {
        this.reviews = reviews;
    }

    @Operation(summary = "The company's review queue, oldest first, with what the AI found")
    @GetMapping("/api/reviews")
    List<CompletionAudits.Audit> queue() {
        return reviews.queue();
    }

    @Operation(summary = "The AI check of a work order's latest submission")
    @GetMapping("/api/work-orders/{id}/audit")
    CompletionAudits.Audit forWorkOrder(@PathVariable UUID id) {
        return reviews.forWorkOrder(id);
    }

    @Operation(summary = "Approve the work after reviewing it")
    @PostMapping("/api/reviews/{id}/approve")
    CompletionAudits.Audit approve(@PathVariable UUID id) {
        return reviews.approve(id);
    }

    @Operation(summary = "Send the work back to the technician, with the reason")
    @PostMapping("/api/reviews/{id}/reject")
    CompletionAudits.Audit reject(@PathVariable UUID id, @Valid @RequestBody RejectWorkOrderRequest request) {
        return reviews.reject(id, request.reason());
    }
}
