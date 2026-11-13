package dev.rashed.dispatch.workorder;

import com.fasterxml.jackson.annotation.JsonIgnore;
import dev.rashed.dispatch.shared.GeoPoint;
import dev.rashed.dispatch.shared.Money;
import dev.rashed.dispatch.shared.Skill;
import dev.rashed.dispatch.shared.TimeWindow;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A work order as the API returns it: a separate type from the entity, so internals never leak. */
public record WorkOrderView(
        UUID id,
        String title,
        String description,
        WorkOrderStatus status,
        String siteAddress,
        GeoPoint site,
        List<String> requiredSkills,
        TimeWindow timeWindow,
        Money budget,
        UUID technicianId,
        String rejectionReason,
        List<ChecklistItemView> checklist,
        Instant createdAt,
        @JsonIgnore long version) { // sent as the ETag header, not in the body

    public record ChecklistItemView(int position, String description) {}

    static WorkOrderView of(WorkOrder workOrder) {
        return new WorkOrderView(
                workOrder.id(),
                workOrder.title(),
                workOrder.description(),
                workOrder.status(),
                workOrder.siteAddress(),
                workOrder.site(),
                workOrder.requiredSkills().stream().map(Skill::code).sorted().toList(),
                workOrder.timeWindow(),
                workOrder.budget(),
                workOrder.technicianId(),
                workOrder.rejectionReason(),
                workOrder.checklist().stream()
                        .map(item -> new ChecklistItemView(item.position(), item.description()))
                        .toList(),
                workOrder.createdAt(),
                workOrder.version());
    }
}
