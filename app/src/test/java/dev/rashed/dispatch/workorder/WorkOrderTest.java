package dev.rashed.dispatch.workorder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import dev.rashed.dispatch.shared.Money;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkOrderTest {

    final UUID tenantId = UUID.randomUUID();

    @Test
    void newWorkOrderStartsAsDraftWithoutTechnician() {
        var workOrder = WorkOrders.draft(tenantId, "Fix POS terminal");

        assertThat(workOrder.status()).isEqualTo(WorkOrderStatus.DRAFT);
        assertThat(workOrder.technicianId()).isNull();
        assertThat(workOrder.budget()).isEqualTo(Money.bdt(250_000));
    }

    @Test
    void rejectsBlankTitle() {
        assertThatIllegalArgumentException().isThrownBy(() -> WorkOrders.draft(tenantId, "  "));
    }

    @Test
    void checklistKeepsOrderAndCannotBeModifiedFromOutside() {
        var workOrder = WorkOrders.draft(tenantId, "Fix POS terminal");
        workOrder.addChecklistItem("Photo of the terminal's error screen");
        workOrder.addChecklistItem("Test transaction succeeds");

        assertThat(workOrder.checklist())
                .extracting(ChecklistItem::position, ChecklistItem::description)
                .containsExactly(
                        tuple(0, "Photo of the terminal's error screen"), tuple(1, "Test transaction succeeds"));
        // The getter returns a copy, so nobody can bypass addChecklistItem's rules.
        assertThatThrownBy(() -> workOrder.checklist().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void checklistHasAnUpperBound() {
        var workOrder = WorkOrders.draft(tenantId, "Fix POS terminal");
        for (int i = 0; i < WorkOrder.MAX_CHECKLIST_ITEMS; i++) {
            workOrder.addChecklistItem("Step " + i);
        }
        assertThatThrownBy(() -> workOrder.addChecklistItem("One too many")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aTechnicianCantStartAnUnfundedJobWhenPaymentsAreRequired() {
        var workOrder = WorkOrders.inStatus(tenantId, UUID.randomUUID(), WorkOrderStatus.ASSIGNED);

        assertThatThrownBy(() -> workOrder.start(true))
                .isInstanceOf(dev.rashed.dispatch.shared.BusinessRuleException.class)
                .hasMessageContaining("hasn't funded");
        assertThat(workOrder.status()).isEqualTo(WorkOrderStatus.ASSIGNED);

        workOrder.markFunded(java.time.Instant.parse("2026-11-19T10:00:00Z"));
        workOrder.markFunded(java.time.Instant.parse("2026-11-19T11:00:00Z")); // a repeat keeps the first time
        workOrder.start(true);
        assertThat(workOrder.status()).isEqualTo(WorkOrderStatus.IN_PROGRESS);
        assertThat(workOrder.fundedAt()).isEqualTo(java.time.Instant.parse("2026-11-19T10:00:00Z"));
    }

    @Test
    void onlyAnAssignedJobCanBeFunded() {
        var draft = WorkOrders.draft(tenantId, "Fix POS terminal");
        assertThatThrownBy(() -> draft.markFunded(java.time.Instant.now()))
                .isInstanceOf(dev.rashed.dispatch.shared.BusinessRuleException.class);
    }

    @Test
    void onlyAnAssignedJobThatHasntStartedCanBeReassigned() {
        var first = UUID.randomUUID();
        var second = UUID.randomUUID();
        var workOrder = WorkOrders.inStatus(tenantId, first, WorkOrderStatus.ASSIGNED);

        assertThatThrownBy(() -> workOrder.reassign(first)).hasMessageContaining("already has");
        workOrder.reassign(second);
        assertThat(workOrder.technicianId()).isEqualTo(second);
        assertThat(workOrder.status()).isEqualTo(WorkOrderStatus.ASSIGNED);

        workOrder.start(false);
        assertThatThrownBy(() -> workOrder.reassign(first)).hasMessageContaining("hasn't started");
    }
}
