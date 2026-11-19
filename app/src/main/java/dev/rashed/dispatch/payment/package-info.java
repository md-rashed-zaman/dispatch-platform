/**
 * Payments (step 4.2, ADR 0015, ADR 0016, docs/diagrams/payment-flow.md). The client funds a work order's budget
 * through a hosted checkout once a technician is assigned. A payment is captured only by Dispatch's own call to
 * the provider, checked against our transaction id, the amount and the currency: the browser's return and the
 * provider's notification only say which payment to check. Unknown outcomes stay open until the provider answers
 * a status query ({@link dev.rashed.dispatch.payment.PaymentReconciler}).
 */
package dev.rashed.dispatch.payment;
