package dev.rashed.dispatch.notification;

import dev.rashed.dispatch.tenancy.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Tag(name = "Live updates", description = "Server-sent events for the dispatcher dashboard")
@RestController
@RequestMapping("/api/live")
class LiveUpdatesController {

    private final LiveUpdates live;

    LiveUpdatesController(LiveUpdates live) {
        this.live = live;
    }

    /**
     * Browsers' EventSource can't send an Authorization header, so the dashboard reads this with fetch (or a
     * fetch-based SSE client) and its bearer token, like every other call.
     */
    @Operation(
            summary = "Stream the company's work order changes",
            description = "text/event-stream: one `work-order.status-changed` event per change, keep-alive comments"
                    + " every 20 s. Reconnect when it closes (after 30 minutes) and reload the list once.")
    @PreAuthorize("hasAnyRole('CLIENT_ADMIN', 'DISPATCHER')")
    @GetMapping(path = "/work-orders", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter workOrders(HttpServletResponse response) {
        response.setHeader("X-Accel-Buffering", "no"); // nginx would otherwise hold events back in its buffer
        response.setHeader("Cache-Control", "no-cache");
        return live.subscribe(TenantContext.current().orElseThrow());
    }
}
