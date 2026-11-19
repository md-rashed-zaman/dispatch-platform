# 15. Escrow funded at assignment, released at approval; a payment is captured only by a server-side check

Date: 2026-11-19
Status: Accepted

## Context

Dispatch connects a paying client (a bank or office) with a technician it doesn't know. The technician wants to know the money exists before driving out; the client doesn't want to pay for work that isn't done. Clients pay through a hosted checkout (SSLCommerz: cards, bKash, Nagad, internet banking), where the customer's browser comes back to us after paying and the provider also notifies us server to server (IPN).

Two questions: **when** does the client pay, and **what** proves that they did?

## Decision

1. **When:** the client funds escrow for the work order's budget once a technician is assigned. Starting work requires a captured payment. The payout to the technician (budget minus the platform fee) is released when the work is approved; a cancellation before work starts refunds the client in full. Flows: [payment-flow.md](../diagrams/payment-flow.md).
2. **What proves it:** only a server-to-server check by Dispatch: the provider's validation API with the `val_id`, or its transaction query with our `tran_id`, returning a valid status for the same `tran_id`, amount and currency. The browser redirect and the IPN only tell us which payment to check. All paths go through one idempotent `confirm(tranId)`.
3. **Unknown outcomes stay unknown** until the provider answers a status query (a scheduled reconciler); a timeout never marks a payment failed and never starts a second transaction for the same attempt.

## Alternatives considered

| Option | Verdict |
|---|---|
| Fund at publish | Rejected: the client pays before anyone has taken the job; if no technician accepts (offers expire, the job is cancelled), every unaccepted job needs a refund, with its charges and delay. |
| Pay after approval (no escrow) | Rejected: the technician carries the risk of not being paid, for a client they've never met. That's the trust problem a marketplace is supposed to solve. |
| Trust the success redirect | Rejected: anyone can open `success_url` with made-up parameters, and a browser that closes after paying never comes back. The provider's own answer to our server is the only fact. |
| Trust the IPN body | Rejected: the IPN endpoint is public; a forged POST with `status=VALID` would fund a job. It's used as a hint, then validated. |
| Capture only by IPN, no polling | Rejected: IPNs get lost (our endpoint down, network). The reconciler queries payments still pending after 15 minutes. |

## Consequences

- One more step for the client between assignment and work starting; the technician sees "waiting for funding". Local runs without a sandbox account switch the gate off (`dispatch.payments.required=false`).
- Payment states: `INITIATED -> PENDING -> CAPTURED / FAILED / EXPIRED`; refunds are separate records, so a captured payment never changes back.
- SSLCommerz has no payout API: technician payouts need a separate rail (bank transfer or bKash disbursement, batched), marked confirmed by a platform admin until one is integrated.
