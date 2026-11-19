# 16. Idempotency keys for payment APIs

Date: 2026-11-19
Status: Accepted

## Context

`POST /api/work-orders/{id}/payments` starts a payment: it creates a payment row and asks SSLCommerz for a payment page. Clients retry: a mobile network drops the response, a user double-clicks, a gateway times out and the browser tries again. A retry must never become a second payment, and the client must get the same answer it would have got the first time.

The request can also be slow (the provider takes up to 10 s), so several copies may be running at once.

## Decision

1. **The client sends an `Idempotency-Key` header** (a UUID per payment attempt, the same on every retry of that attempt). Required on `POST .../payments`: 400 without it.
2. **The first request with a key claims it** in `idempotency_key` (V19), per company: `INSERT ... ON CONFLICT DO NOTHING` with a SHA-256 of the request (method and path; the endpoint takes no body). The claim is its own short transaction, so concurrent copies see it at once.
3. **Later requests with the same key:**
   - finished, same request: the stored status and body, with `Idempotent-Replayed: true`;
   - still running: 409 `idempotency-in-progress` with `Retry-After: 1`;
   - a different request: 422 `idempotency-key-reused` (a client bug, never "the same request").
4. **Errors aren't stored.** A request that fails gives its key back, so a retry runs again. What keeps that retry from paying twice is the payment's own rule, not the key: one open or captured payment per work order (a partial unique index), and only the request that created a payment asks the provider for its page.
5. **Keys live a day.** The nightly reconciliation deletes older ones; a retry a day later is a new request.

## Alternatives considered

| Option | Verdict |
|---|---|
| No key: rely on "one live payment per work order" | It already prevents a second payment, and stays as the backstop. But a retry would get a different answer (409 "in progress" or the existing page) rather than the first response, and a key generalizes to future money endpoints (refunds, payouts) where no such natural rule exists. |
| Store errors too (like Stripe for 4xx) | Not needed here: a retry of a refused request is refused again by the same rules, and a 503 must be retryable. Simpler to give the key back on any error. |
| Hold the request until the first finishes, instead of 409 | Ties up a server thread per waiting copy for up to the provider's timeout. A 409 with `Retry-After` is cheap and clients already retry. |
| Redis for keys | The key and the payment must agree; both in PostgreSQL keeps one source of truth with row-level security, and the load is tiny. |

## Consequences

- The web app sends one key per click and keeps it if the request may have reached the server (a network error), so its retry is the same attempt.
- Proven by `PaymentFlowTests`: five concurrent requests with one key make one payment and one provider call; a reused key for another work order is 422.
- "Exactly once" is at-least-once delivery plus idempotency: the key for the client's request, the conditional update and unique `val_id` for the provider's notices.
