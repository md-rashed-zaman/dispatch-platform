# 17. A double-entry ledger in PostgreSQL, checked by the database

Date: 2026-11-19
Status: Accepted

## Context

From step 4.2 Dispatch holds clients' money: captured into escrow, released at approval (minus a 10% platform fee), paid out to technicians, refunded on cancellation, minus the gateway's charge. "Where is the money now, and did we lose any?" must have one answer that a bug can't quietly change.

A balance column per account ("escrow: 5,000") can't answer it: an update that adds to one balance and forgets the other leaves no trace, and nobody can say afterwards which change caused a wrong number.

## Decision

1. **Double entry.** Every movement is one journal entry with at least two lines; its debits equal its credits. A fixed chart of six accounts (V20):

   | Account | Kind | Holds |
   |---|---|---|
   | `bank` | asset | the platform's bank account |
   | `gateway_clearing` | asset | owed to us by the payment gateway |
   | `gateway_fees` | expense | the gateway's charge |
   | `client_escrow` | liability | clients' money held until approval |
   | `technician_payable` | liability | owed to technicians (per technician) |
   | `platform_revenue` | revenue | platform fees earned |

2. **Checked twice.** The `Ledger.Entry` constructor refuses an unbalanced entry; deferred constraint triggers check every entry again at commit, so code that skips the class can't commit one either.
3. **Append-only.** The app role can't update or delete journal rows, and triggers refuse it for everyone. A wrong entry is cancelled by a reversing entry (`Ledger.reverse`), at most once.
4. **Posted with the change that moves the money**, in the same transaction: the capture with the payment's state, the approval's split with the payout record, the payout with the work order becoming PAID, the refund when the provider says it's done.
5. **Idempotent.** Each entry has a reference ("capture:&lt;payment&gt;", "approval:&lt;work order&gt;") unique per company: a redelivered event posts nothing twice.
6. **Integers.** Minor units in `BIGINT`; the fee split rounds the fee down and gives the remainder to the technician, so the parts always equal the whole.
7. **Per company, under row-level security.** A SECURITY DEFINER aggregate (`ledger_totals()`) gives the platform-wide sums for the platform admin and the nightly check: debits must equal credits.

## Alternatives considered

| Option | Verdict |
|---|---|
| A balance column per account, updated in place | Rejected: no history, and a half-done update is invisible. Balances are a query over the lines instead (fast enough at this scale; cache or snapshot per day if it isn't). |
| An accounting engine or a ledger service (TigerBeetle, Formance, a SaaS ledger) | Rejected for now: another system to run and keep consistent with PostgreSQL, where the payments live. Worth it at volumes where one table's writes become the limit. |
| Accounts per company and per technician (one row each) | Rejected: thousands of rows to create and keep. Six accounts, with the company (`tenant_id`) and technician on each line, answer the same questions. |
| Checking balance only in Java | Rejected: one forgotten code path or a manual SQL fix could commit an unbalanced entry. The database is the last line. |

## Consequences

- Proven by a property test: 25 seeded random sequences of 50 movements (capture, fee, approval, payout, refund, repeats) keep the platform balanced, every job's escrow all or nothing, and nothing owed negative. A planted bug (a refund taken from technician_payable) fails it with its seed and step.
- The gateway's actual settlement to our bank (gateway_clearing to bank) isn't posted yet: it needs the gateway's settlement report. Until then `gateway_clearing` shows what the gateway owes us.
- Reporting (statements, period closes) reads the lines; nothing else is needed for this project.
