package dev.rashed.dispatch.payment;

import dev.rashed.dispatch.ledger.Ledger;
import dev.rashed.dispatch.shared.NotFoundException;
import dev.rashed.dispatch.workorder.WorkOrderFunding;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Tag(
        name = "Ledger and payouts",
        description = "Where the money is: journal entries, platform totals, technician payouts")
@RestController
class MoneyController {

    record ConfirmPayoutRequest(@NotBlank @Size(max = 100) String bankReference) {}

    /** Per account, across every company. */
    record AccountTotal(String account, long debits, long credits) {}

    /** {@code balanced}: every debit across the platform has its credit, so no money was created or lost. */
    record Totals(List<AccountTotal> accounts, long debits, long credits, boolean balanced) {}

    private final PayoutService payouts;
    private final Ledger ledger;
    private final WorkOrderFunding workOrders;

    MoneyController(PayoutService payouts, Ledger ledger, WorkOrderFunding workOrders) {
        this.payouts = payouts;
        this.ledger = ledger;
        this.workOrders = workOrders;
    }

    @Operation(summary = "A work order's journal entries: every movement of its money, oldest first")
    @GetMapping("/api/work-orders/{id}/ledger")
    @PreAuthorize("hasAnyRole('CLIENT_ADMIN', 'DISPATCHER')")
    @Transactional(readOnly = true)
    List<Ledger.PostedEntry> workOrderLedger(@PathVariable UUID id) {
        workOrders.snapshot(id).orElseThrow(() -> new NotFoundException("Work order", id)); // 404 for another company's
        return ledger.forWorkOrder(id);
    }

    @Operation(summary = "Debits and credits per account across the platform (platform admins)")
    @GetMapping("/api/ledger/totals")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    Totals totals() {
        var accounts = ledger.platformTotals().entrySet().stream()
                .map(e -> new AccountTotal(e.getKey().name().toLowerCase(), e.getValue()[0], e.getValue()[1]))
                .toList();
        long debits = accounts.stream().mapToLong(AccountTotal::debits).sum();
        long credits = accounts.stream().mapToLong(AccountTotal::credits).sum();
        return new Totals(accounts, debits, credits, debits == credits);
    }

    @Operation(summary = "Payouts waiting to be paid to technicians, every company (platform admins)")
    @GetMapping("/api/payouts")
    List<PayoutService.PayoutView> pendingPayouts() {
        return payouts.pending();
    }

    @Operation(summary = "Record that a technician was paid, with the bank's reference (platform admins)")
    @PostMapping("/api/payouts/{id}/confirm")
    PayoutService.PayoutView confirm(@PathVariable UUID id, @Valid @RequestBody ConfirmPayoutRequest request) {
        return payouts.confirm(id, request.bankReference().strip());
    }
}
