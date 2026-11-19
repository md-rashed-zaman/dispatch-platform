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
| **MCP** | Model Context Protocol: how an AI client (Claude) calls tools a server offers. Dispatch's tools are at `/api/mcp`. | `assistant` module, ADR 0019 |
| **Feature flag** | A switch that turns a feature on for some companies: on or off for everyone, an allow and a deny list, and a rollout percentage. | `flags` module, `feature_flag` table |
| **Bucket** | A company's fixed number from 0 to 99 for a flag (a hash of the flag and the company): on when it's below the rollout percentage. | `Flag.bucket` |
| **Funding** | The client paying a work order's budget into escrow, after assignment and before work starts. | `payment`, `EscrowFunded` |
| **Hosted checkout** | The provider's payment page the client is sent to; card and wallet details never reach Dispatch. | SSLCommerz `GatewayPageURL` |
| **IPN** | Instant Payment Notification: the provider's server-to-server POST after a payment. A hint about which payment to check, never proof. | `POST /api/payments/sslcommerz/ipn` |
| **Capture** | A payment confirmed by Dispatch's own call to the provider (validation or status query): the money is in escrow. | payment status `CAPTURED` |
| **Idempotency key** | A value the client sends with a request that moves money (`Idempotency-Key` header), the same on every retry: the retry gets the first answer instead of doing it again. | `idempotency_key` table, ADR 0016 |
| **Journal entry** | One movement of money in the ledger: two or more lines whose debits equal their credits. Never edited; a wrong one is reversed by a mirror entry. | `journal_entry`, `journal_line`, ADR 0017 |
| **Refund** | Returning a captured payment to the client (the job was cancelled before work started). A second movement of money; the payment stays captured. | `refund` table |
| **Reconciliation** | Comparing our payment states with the provider's, to settle unknown outcomes and find mismatches. | `PaymentReconciler` |
| **SLA** | Time limits for a work order (e.g. assigned within 2 hours of publishing). | SLA policy job |

## Words we avoid

| Avoid | Use instead | Why |
|---|---|---|
| Job, task, ticket | Work order | One word for one concept |
| Worker, vendor, engineer | Technician | Same |
| Customer, company, org | Tenant (technical) / client (business talk) | "Customer" is ambiguous between client and technician |
| Invite, request | Offer | Same |
