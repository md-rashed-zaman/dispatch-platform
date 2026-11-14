package dev.rashed.dispatch.workorder;

import java.time.Instant;
import java.util.UUID;

/** The public contract for {@code work-order.sla-breached} version 1 on Kafka (see WorkOrderStatusChangedV1). */
public record WorkOrderSlaBreachedV1(UUID workOrderId, String title, Instant waitingSince, Instant occurredAt) {

    public static final String TYPE = "work-order.sla-breached";
    public static final int VERSION = 1;
}
