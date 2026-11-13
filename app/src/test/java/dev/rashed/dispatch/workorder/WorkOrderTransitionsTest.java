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
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import dev.rashed.dispatch.shared.BusinessRuleException;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The lifecycle spec, written before the code: every command tried from every status. Allowed pairs move
 * to the expected status; every other pair must fail and leave the work order untouched.
 */
class WorkOrderTransitionsTest {

    static final UUID TECHNICIAN = UUID.randomUUID();

    enum Command {
        PUBLISH(WorkOrder::publish),
        ROUTE(WorkOrder::route),
        OFFERS_EXPIRED(WorkOrder::offersExpired),
        ASSIGN(w -> w.assign(TECHNICIAN)),
        START(WorkOrder::start),
        SUBMIT(WorkOrder::submit),
        APPROVE(WorkOrder::approve),
        REJECT(w -> w.reject("Photo of the receipt is missing")),
        MARK_PAID(WorkOrder::markPaid),
        CANCEL(WorkOrder::cancel);

        final Consumer<WorkOrder> action;

        Command(Consumer<WorkOrder> action) {
            this.action = action;
        }
    }

    /** The only allowed moves: (from, command) -> to. Mirrors the diagram in docs/diagrams/work-order-lifecycle.md. */
    static final Map<String, WorkOrderStatus> ALLOWED = Map.ofEntries(
            Map.entry(DRAFT + ":" + Command.PUBLISH, PUBLISHED),
            Map.entry(PUBLISHED + ":" + Command.ROUTE, ROUTED),
            Map.entry(ROUTED + ":" + Command.OFFERS_EXPIRED, PUBLISHED),
            Map.entry(ROUTED + ":" + Command.ASSIGN, ASSIGNED),
            Map.entry(ASSIGNED + ":" + Command.START, IN_PROGRESS),
            Map.entry(IN_PROGRESS + ":" + Command.SUBMIT, SUBMITTED),
            Map.entry(SUBMITTED + ":" + Command.APPROVE, APPROVED),
            Map.entry(SUBMITTED + ":" + Command.REJECT, IN_PROGRESS),
            Map.entry(APPROVED + ":" + Command.MARK_PAID, PAID),
            Map.entry(DRAFT + ":" + Command.CANCEL, CANCELLED),
            Map.entry(PUBLISHED + ":" + Command.CANCEL, CANCELLED),
            Map.entry(ROUTED + ":" + Command.CANCEL, CANCELLED),
            Map.entry(ASSIGNED + ":" + Command.CANCEL, CANCELLED));

    static Stream<Arguments> everyStatusAndCommand() {
        return Arrays.stream(WorkOrderStatus.values())
                .flatMap(status -> Arrays.stream(Command.values()).map(command -> Arguments.of(status, command)));
    }

    @ParameterizedTest(name = "{0} + {1}")
    @MethodSource("everyStatusAndCommand")
    void onlyAllowedTransitionsHappen(WorkOrderStatus from, Command command) {
        var workOrder = WorkOrders.inStatus(from);
        var expected = ALLOWED.get(from + ":" + command);

        if (expected != null) {
            command.action.accept(workOrder);
            assertThat(workOrder.status()).isEqualTo(expected);
        } else {
            assertThatThrownBy(() -> command.action.accept(workOrder))
                    .isInstanceOf(InvalidTransitionException.class)
                    .hasMessageContaining(from.name());
            assertThat(workOrder.status()).isEqualTo(from);
        }
    }

    @Test
    void cannotApproveACancelledWorkOrder() {
        var workOrder = WorkOrders.inStatus(CANCELLED);

        assertThatThrownBy(workOrder::approve)
                .isInstanceOf(InvalidTransitionException.class)
                .hasMessage("Can't approve a work order that is CANCELLED");
    }

    @Test
    void publishingNeedsAChecklist() {
        var workOrder = WorkOrders.draft(UUID.randomUUID(), "No checklist yet");

        assertThatThrownBy(workOrder::publish)
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("checklist");
        assertThat(workOrder.status()).isEqualTo(DRAFT);
    }

    @Test
    void assignmentRecordsTheTechnician() {
        var workOrder = WorkOrders.inStatus(ROUTED);

        workOrder.assign(TECHNICIAN);

        assertThat(workOrder.technicianId()).isEqualTo(TECHNICIAN);
    }

    @Test
    void rejectionNeedsAReasonAndResubmittingClearsIt() {
        var workOrder = WorkOrders.inStatus(SUBMITTED);
        assertThatThrownBy(() -> workOrder.reject(" ")).isInstanceOf(IllegalArgumentException.class);

        workOrder.reject("Photo of the receipt is missing");
        assertThat(workOrder.rejectionReason()).isEqualTo("Photo of the receipt is missing");

        workOrder.submit();
        assertThat(workOrder.rejectionReason()).isNull();
    }

    @Test
    void onlyDraftsCanBeRevised() {
        var draft = WorkOrders.inStatus(DRAFT);
        draft.revise(new WorkOrderChanges("New title", null, null, null, null, null, null));
        assertThat(draft.title()).isEqualTo("New title");

        var published = WorkOrders.inStatus(PUBLISHED);
        assertThatThrownBy(
                        () -> published.revise(new WorkOrderChanges("New title", null, null, null, null, null, null)))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void everyTransitionAnnouncesAStatusChange() {
        var workOrder = WorkOrders.inStatus(DRAFT);

        workOrder.publish();
        workOrder.cancel();

        assertThat(workOrder.pendingEvents())
                .extracting(WorkOrderStatusChanged::from, WorkOrderStatusChanged::to)
                .containsExactly(tuple(DRAFT, PUBLISHED), tuple(PUBLISHED, CANCELLED));
    }
}
