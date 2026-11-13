package dev.rashed.dispatch.workorder;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The technician sees this reason, so it should say what to fix. */
public record RejectWorkOrderRequest(
        @NotBlank @Size(max = 1000) String reason) {}
