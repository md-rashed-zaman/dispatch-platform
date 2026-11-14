package dev.rashed.dispatch.workorder;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The public contract for {@code work-order.status-changed} version 1 on Kafka. Separate from the internal
 * event, so internal refactoring can't break consumers: add fields freely, never rename or remove them
 * (that's version 2, on a new topic).
 */
public record WorkOrderStatusChangedV1(
        UUID workOrderId,
        String title, // added after the first release: older events on the topic don't have it
        WorkOrderStatus from,
        WorkOrderStatus to,
        @Nullable UUID technicianId,
        Instant occurredAt) {

    public static final String TYPE = "work-order.status-changed";
    public static final int VERSION = 1;
}
