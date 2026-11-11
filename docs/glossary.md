# Glossary (ubiquitous language)

These words mean exactly this, in conversations, docs and code. If a word changes, change it here and in the code together.

| Term | Meaning | In code |
|---|---|---|
| **Tenant** | A client company using Dispatch. All its work orders, users and payments belong to it. | `tenantId` on tenant-owned rows |
| **Client admin** | User who manages a tenant's account and users. | role `CLIENT_ADMIN` |
| **Dispatcher** | Tenant user who creates, publishes, reviews and approves work orders. | role `DISPATCHER` |
| **Technician** | Freelancer who performs on-site work for any tenant. Not a tenant member. | `Technician`, role `TECHNICIAN` |
| **Platform admin** | Operator of Dispatch itself. | role `PLATFORM_ADMIN` |
| **Work order** | A request for on-site work at a site, with required skills, a time window, a checklist and a budget. | `WorkOrder` (aggregate root) |
| **Site** | The location where the work happens. | `GeoPoint` + address |
| **Checklist item** | One thing the technician must do or prove (e.g. "photo of installed router"). Locked after submission. | `ChecklistItem` |
| **Time window** | When the work may be done (start and end). | `TimeWindow` |
| **Publish** | Make a draft work order available for matching. | `WorkOrder.publish()` |
| **Matching** | Finding technicians whose skills and location fit a work order, ranked best first. | `matching` module |
| **Offer** | An invitation for one technician to take one work order, with an expiry. | `Offer` |
| **Route / routing** | Sending offers for a work order. A work order is ROUTED while offers are open. | status `ROUTED` |
| **Accept** | A technician takes an offer. The first acceptance wins; the rest expire. | `Offer.accept()` |
| **Assignment** | The work order now belongs to exactly one technician. | status `ASSIGNED`, `technicianId` |
| **Escrow** | Money the client has paid that is held until the work is approved. | ledger account `client_escrow` |
| **Submit** | Technician declares the work done, with notes and photos. | status `SUBMITTED` |
| **Completion audit** | AI check of submitted notes and photos against the checklist. Advisory only. | `AuditResult` |
| **Review** | A dispatcher deciding on a submission (approve or reject). | review queue |
| **Approve** | Accept the work as done. Triggers the payout. | status `APPROVED` |
| **Reject** | Send the work back to the technician with a reason. | `WorkRejected` |
| **Payout** | Releasing escrow to the technician, minus the platform fee. | `PayoutReleased` |
| **Platform fee** | Dispatch's share of each paid work order. | ledger account `platform_revenue` |
| **SLA** | Time limits for a work order (e.g. assigned within 2 hours of publishing). | SLA policy job |

## Words we avoid

| Avoid | Use instead | Why |
|---|---|---|
| Job, task, ticket | Work order | One word for one concept |
| Worker, vendor, engineer | Technician | Same |
| Customer, company, org | Tenant (technical) / client (business talk) | "Customer" is ambiguous between client and technician |
| Invite, request | Offer | Same |
