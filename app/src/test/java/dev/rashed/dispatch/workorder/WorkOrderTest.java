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
}
