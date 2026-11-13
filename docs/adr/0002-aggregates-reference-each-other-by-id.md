# 2. Aggregates reference each other by ID

Date: 2026-11-13
Status: Accepted

## Context

A work order belongs to a tenant (`Company`) and later to a `Technician`. An `Offer` links a work order and a technician. In JPA the obvious mapping is object references: `@ManyToOne Company company`, `@ManyToOne Technician technician`.

That makes everything reachable from everything. Loading a work order can pull in the company and technician (and whatever they point to), saving one can cascade into another aggregate, and one transaction can quietly change two aggregates that other users are editing. It also couples the `workorder` module to the internals of `tenancy` and `technician`, which Spring Modulith would flag.

## Decision

- An aggregate is a cluster of objects that change together, with one root that enforces their rules. `WorkOrder` + `ChecklistItem` is one aggregate; `Company`, `Technician` and `Offer` are each their own.
- Inside an aggregate, use object references (`WorkOrder` has a `List<ChecklistItem>`).
- Between aggregates, store only the ID (`UUID tenantId`, `UUID technicianId`, `UUID workOrderId`).
- Foreign keys stay in the database for integrity.
- `Offer` is its own aggregate, not part of `WorkOrder`: many technicians respond to offers for the same work order at the same moment, and each offer must change without locking the whole work order.

## Alternatives considered

- `@ManyToOne` object references everywhere: rejected. Convenient navigation, but it blurs transaction boundaries, invites N+1 and lazy loading surprises, and couples modules.
- Offers inside the `WorkOrder` aggregate: rejected. Every accept or decline would load and version-check the whole work order, so concurrent responses would conflict with each other.

## Consequences

Good: each aggregate loads, locks and saves on its own; modules depend only on IDs; the boundaries match what Modulith verifies; extracting a module into its own service later is mostly mechanical.

Bad / accepted costs: showing "work order with technician name" needs a second query or a read-model query joining tables; there is no `workOrder.getTechnician().getName()`.
