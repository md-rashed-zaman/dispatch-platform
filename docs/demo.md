# 3-minute demo

Record the terminal running `./scripts/demo.sh` (Enter between steps) next to this talk track. Aim for one sentence per point; the output on screen carries the rest.

Before recording: stack up, app running, seed loaded, terminal font large, one window.

| Time | Script step | Say |
|---|---|---|
| 0:00 | (title) | "Dispatch is a marketplace for on-site technical work: a bank needs a POS terminal fixed, and Dispatch finds a qualified technician nearby. I'll show the core flow, and the three hard problems behind it." |
| 0:15 | Log in | "Two real Keycloak logins. The bank's token carries its tenant; the technician's carries a technician id. Everything after this is authorized from those claims." |
| 0:30 | Publish | "Publishing returns at once; matching reacts to the event right after the commit and searches 50,000 technicians for the ten nearest with the skill. That query takes under a millisecond. Indexes alone didn't get it there: Postgres estimated 5 matching rows when there were 22,500, so I rewrote the condition it misjudged." |
| 1:00 | Offer | "Rahim sees the offer: the job, the address, the budget, and when it expires." |
| 1:15 | Double tap | "Here's the signature problem. Many technicians accept at the same moment and exactly one may win. One conditional UPDATE decides it inside Postgres, so it holds across any number of app instances. A test fires 50 accepts at once: one winner, 49 'already taken'. Here Rahim double-taps: both answers say he won, because I found and fixed that exact case while building this demo." |
| 1:50 | Upload | "The photo goes straight from the phone to object storage. The API signs a URL for one key, one content type and one size, valid five minutes, then checks what actually arrived, down to the first bytes. The same code runs on Amazon S3." |
| 2:20 | Submit, approve | "Rahim submits, the bank sees the photo and approves. Every step is a lifecycle command; approving a cancelled job is a 409, and the spec tests all 90 command-status pairs." |
| 2:35 | Other bank | "Another bank asks for this job and gets 404, not 403. Two layers hide it: Hibernate's tenant filter and Postgres row-level security, which still holds if a query skips the first." |
| 2:50 | (close) | "Each decision has an ADR and each claim a test. Next phase: events, outbox, and observability." |

If something fails mid-recording, `docs/failure-modes.md` says what the API does when each dependency is down.
