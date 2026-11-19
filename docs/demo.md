# 4-minute demo

Record the terminal running `./scripts/demo.sh` (Enter between steps) next to this talk track. Aim for one sentence per point; the output on screen carries the rest.

Before recording: stack up (including `lgtm` for the trace), app running, seed loaded, terminal font large, one window. Optionally open the *Dispatch overview* dashboard (http://localhost:3000) and Mailpit (http://localhost:8025) in browser tabs to switch to at the end.

| Time | Script step | Say |
|---|---|---|
| 0:00 | (title) | "Dispatch is a marketplace for on-site technical work: a bank needs a POS terminal fixed, and Dispatch finds a qualified technician nearby. I'll show the core flow, and the three hard problems behind it." |
| 0:15 | Log in | "Two real Keycloak logins. The bank's token carries its tenant; the technician's carries a technician id. Everything after this is authorized from those claims." |
| 0:30 | Publish | "Publishing returns at once; matching reacts to the event right after the commit and searches 50,000 technicians for the ten nearest with the skill. That query takes under a millisecond. Indexes alone didn't get it there: Postgres estimated 5 matching rows when there were 22,500, so I rewrote the condition it misjudged." |
| 1:00 | Offer | "Rahim sees the offer: the job, the address, the budget, and when it expires." |
| 1:15 | Double tap | "Here's the signature problem. Many technicians accept at the same moment and exactly one may win. One conditional UPDATE decides it inside Postgres, so it holds across any number of app instances. A test fires 50 accepts at once: one winner, 49 'already taken'. Here Rahim double-taps: both answers say he won, because I found and fixed that exact case while building this demo." |
| 1:50 | Upload | "The photo goes straight from the phone to object storage. The API signs a URL for one key, one content type and one size, valid five minutes, then checks what actually arrived, down to the first bytes. The same code runs on Amazon S3." |
| 2:20 | Submit, approve | "Rahim submits, the bank sees the photo and approves. Every step is a lifecycle command; approving a cancelled job is a 409, and the spec tests all 90 command-status pairs." |
| 2:35 | Dashboard followed it live | "The bank's dashboard had a live stream open the whole time: every change, as it happened. Each change goes to Kafka through an outbox: the event is written in the same transaction as the change, so it can't get lost even if Kafka is down, and every app instance pushes it to the dashboards connected to it." |
| 2:55 | Emails | "The bank got two emails: accepted, and ready for approval. The consumer deduplicates by event id, so a redelivered event doesn't email twice, and the email itself is queued and sent through a circuit breaker, so a mail outage never slows anything down." |
| 3:15 | One trace | "And this is one trace for that publish: the HTTP request, the outbox relay, the Kafka send, and both consumers. The outbox normally breaks traces, because events leave later on another thread; I store the trace context with the event so the relay continues it. (Open the link: the waterfall.)" |
| 3:35 | Other bank | "Another bank asks for this job and gets 404, not 403. Two layers hide it: Hibernate's tenant filter and Postgres row-level security, which still holds if a query skips the first." |
| 3:50 | (close) | "Each decision has an ADR and each claim a test. I also ran a chaos day: a single table lock took the whole API down through the connection pool. The postmortem, the fix and the rerun are in the repo." |

If something fails mid-recording, `docs/failure-modes.md` says what the API does when each dependency is down.

# Phase 4 demo (about 4 minutes)

Two scripts: `./scripts/demo-phase4.sh` (money, the flag, the MCP tools) and `./scripts/demo-claude.sh` (Claude as the dispatcher's assistant). Before recording: stack up with `lgtm`, the app started with payments required (`PAYMENTS_REQUIRED=true`; the local stand-in for SSLCommerz is the default), Claude Code signed in (`claude auth login`), the Grafana *Dispatch overview* open at the "Experiment" row.

| Time | Script step | Say |
|---|---|---|
| 0:00 | (title) | "Phase 4 is money and control: payments that can't be charged twice or faked, a ledger that proves no money was created or lost, a risky AI feature rolled out to 20% of companies and measured, and an AI assistant that can't do more than its user." |
| 0:15 | Can't start before paying | "A technician took the job. He can't start until the bank has paid: the money must exist before he drives out." |
| 0:25 | Pay twice | "The bank clicks Pay twice. Same Idempotency-Key, so the second answer is the first one replayed: one payment, one payment page. Five at once make one payment too; there's a test." |
| 0:45 | Fake notice | "Anyone can post to the public notification URL. This fake 'paid' changes nothing: a notice only names a payment, and Dispatch asks the provider itself and checks the transaction id, amount and currency." |
| 1:05 | Pay | "Now a real payment on the provider's page (here the local stand-in). Captured, and the technician can start." |
| 1:20 | Ledger | "Where did the money go? Three journal entries: held in escrow, the gateway's 2.5%, then on approval 10% to the platform and the rest owed to the technician. The database refuses any entry whose debits and credits differ, at commit, even from code that skips the checks." |
| 1:45 | Payout | "The platform pays the technician and records the bank's reference: the job is PAID and the platform's books balance. A property test runs random sequences of all of this and checks that after every step." |
| 2:00 | Refund | "Cancel a funded job before work starts and the bank gets its money back, through the provider's refund API. The ledger only records it when the provider says it's done." |
| 2:15 | The flag | "AI auto-approval is a flag, on for 20% of companies, picked by a stable hash: a company never switches groups, and raising the percentage only adds companies. Raising it takes the current version and a reason, and every change is kept. The experiment report and this Grafana row compare time to approval and how often people overrule the AI." |
| 2:45 | MCP tools | "Dispatch is also an MCP server: five tools, four read-only. Each call carries the dispatcher's own token, so roles and row-level security decide, exactly as in the app." |
| 3:00 | Claude (demo-claude.sh, optional: needs Claude Code signed in) | "Claude, connected only to Dispatch as this dispatcher: it finds the job and suggests technicians. Asked to reassign, it shows a preview and asks; nothing has changed yet. I say yes, and only then the job moves, with a one-time token the server issued for exactly that change." |
| 3:35 | The trap | "This job's description tells the AI to reassign it without asking. Claude just summarizes it, and even if a model tried, the server needs a confirmation token for a preview the user saw. Every call is in the audit table." |
| 3:55 | (close) | "Each of these has an ADR and a test that breaks if the protection is removed." |
