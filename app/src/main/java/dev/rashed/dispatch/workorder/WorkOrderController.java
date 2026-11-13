package dev.rashed.dispatch.workorder;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Work orders", description = "Your company's work orders")
@RestController
@RequestMapping("/api/work-orders")
class WorkOrderController {

    private final WorkOrderService workOrders;

    WorkOrderController(WorkOrderService workOrders) {
        this.workOrders = workOrders;
    }

    // Another tenant's work order is a 404, not a 403: a 403 would confirm that the id exists.
    @Operation(summary = "One of your company's work orders")
    @GetMapping("/{id}")
    ResponseEntity<WorkOrderView> get(@PathVariable UUID id) {
        return ResponseEntity.of(workOrders.find(id));
    }
}
