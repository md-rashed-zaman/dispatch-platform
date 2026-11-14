package dev.rashed.dispatch.workorder;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Assigned work", description = "Jobs assigned to you, the technician")
@RestController
@RequestMapping("/api/work-orders/{id}")
class AssignedWorkController {

    private final AssignedWorkService work;

    AssignedWorkController(AssignedWorkService work) {
        this.work = work;
    }

    @Operation(summary = "Start work on a job assigned to you")
    @PostMapping("/start")
    ResponseEntity<WorkOrderView> start(@PathVariable UUID id) {
        return withETag(work.start(id));
    }

    @Operation(summary = "Submit finished work for the company to approve")
    @PostMapping("/submit")
    ResponseEntity<WorkOrderView> submit(@PathVariable UUID id) {
        return withETag(work.submit(id));
    }

    private static ResponseEntity<WorkOrderView> withETag(WorkOrderView view) {
        return ResponseEntity.ok().eTag(String.valueOf(view.version())).body(view);
    }
}
