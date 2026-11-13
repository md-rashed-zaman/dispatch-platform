package dev.rashed.dispatch.workorder;

import dev.rashed.dispatch.shared.GeoPoint;
import dev.rashed.dispatch.shared.Money;
import dev.rashed.dispatch.shared.TimeWindow;
import java.util.Set;

/** A partial edit of a draft: {@code null} means "leave as is". */
public record WorkOrderChanges(
        String title,
        String description,
        String siteAddress,
        GeoPoint site,
        Set<String> requiredSkills,
        TimeWindow timeWindow,
        Money budget) {}
