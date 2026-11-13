package dev.rashed.dispatch.workorder;

import dev.rashed.dispatch.shared.GeoPoint;
import dev.rashed.dispatch.shared.Money;
import dev.rashed.dispatch.shared.TimeWindow;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Test data builder: a valid draft, so each test only spells out what it cares about. */
final class WorkOrders {

    static WorkOrder draft(UUID tenantId, String title) {
        var start = Instant.parse("2026-12-01T04:00:00Z"); // 10:00 in Dhaka
        return WorkOrder.draft(
                tenantId,
                title,
                "POS terminal at the counter shows 'communication error' since this morning.",
                "House 12, Road 5, Dhanmondi, Dhaka",
                new GeoPoint(23.7465, 90.3760),
                List.of("pos-terminal", "networking"),
                new TimeWindow(start, start.plus(Duration.ofHours(4))),
                Money.bdt(250_000));
    }

    private WorkOrders() {}
}
