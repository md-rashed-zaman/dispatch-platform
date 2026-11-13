package dev.rashed.dispatch.workorder;

import static dev.rashed.dispatch.workorder.WorkOrderStatus.APPROVED;
import static dev.rashed.dispatch.workorder.WorkOrderStatus.ASSIGNED;
import static dev.rashed.dispatch.workorder.WorkOrderStatus.CANCELLED;
import static dev.rashed.dispatch.workorder.WorkOrderStatus.DRAFT;
import static dev.rashed.dispatch.workorder.WorkOrderStatus.IN_PROGRESS;
import static dev.rashed.dispatch.workorder.WorkOrderStatus.PAID;
import static dev.rashed.dispatch.workorder.WorkOrderStatus.PUBLISHED;
import static dev.rashed.dispatch.workorder.WorkOrderStatus.ROUTED;
import static dev.rashed.dispatch.workorder.WorkOrderStatus.SUBMITTED;

import java.util.EnumSet;
import java.util.Set;

/**
 * The work order lifecycle as a table: each command, the statuses it may start from, and where it leads.
 * Keyed by command, not by target status, because two commands can lead to the same status from different
 * places (start: ASSIGNED to IN_PROGRESS, reject: SUBMITTED to IN_PROGRESS). Drawn in
 * docs/diagrams/work-order-lifecycle.md.
 */
enum Transition {
    PUBLISH("publish", PUBLISHED, DRAFT),
    ROUTE("route", ROUTED, PUBLISHED),
    OFFERS_EXPIRED("expire the offers of", PUBLISHED, ROUTED),
    ASSIGN("assign", ASSIGNED, ROUTED),
    START("start", IN_PROGRESS, ASSIGNED),
    SUBMIT("submit", SUBMITTED, IN_PROGRESS),
    APPROVE("approve", APPROVED, SUBMITTED),
    REJECT("reject", IN_PROGRESS, SUBMITTED),
    MARK_PAID("mark as paid", PAID, APPROVED),
    // Not from IN_PROGRESS on: once work has started, cancelling is a business process (who pays for the
    // visit?), not a click.
    CANCEL("cancel", CANCELLED, DRAFT, PUBLISHED, ROUTED, ASSIGNED);

    final String verb;
    final WorkOrderStatus to;
    final Set<WorkOrderStatus> from;

    Transition(String verb, WorkOrderStatus to, WorkOrderStatus first, WorkOrderStatus... rest) {
        this.verb = verb;
        this.to = to;
        this.from = EnumSet.of(first, rest);
    }
}
