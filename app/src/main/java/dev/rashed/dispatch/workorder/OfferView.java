package dev.rashed.dispatch.workorder;

import dev.rashed.dispatch.shared.GeoPoint;
import dev.rashed.dispatch.shared.Money;
import dev.rashed.dispatch.shared.TimeWindow;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** An offer as the technician sees it: enough about the job to decide, nothing about other offers. */
public record OfferView(
        UUID id,
        OfferStatus status,
        Instant expiresAt,
        UUID workOrderId,
        String title,
        String description,
        String siteAddress,
        GeoPoint site,
        List<String> requiredSkills,
        TimeWindow timeWindow,
        Money budget) {

    OfferView withStatus(OfferStatus newStatus) {
        return new OfferView(
                id,
                newStatus,
                expiresAt,
                workOrderId,
                title,
                description,
                siteAddress,
                site,
                requiredSkills,
                timeWindow,
                budget);
    }
}
