# 5. Conditional UPDATE for job acceptance

Date: 2026-11-14
Status: Accepted

## Context

Publishing a work order offers it to the 10 nearest technicians who have the skills. They decide on their phones, and several can tap **Accept** within the same few milliseconds. Exactly one must get the job, and the others must hear "already taken", not "congratulations". Two technicians driving to the same branch costs both of them half a day and costs the client their trust.

The obvious code is a check-then-act race:

```java
var workOrder = repository.findById(id);      // both requests read ROUTED
if (workOrder.status() == ROUTED) {           // both pass the check
    workOrder.assign(technicianId);           // both write; the second silently overwrites the first
    repository.save(workOrder);
}
```

Production runs several app instances, so whatever decides the winner has to work across processes.

## Decision

The database decides, in one statement that checks and writes together:

```sql
UPDATE work_order SET status = 'ASSIGNED', technician_id = :me, version = version + 1
WHERE id = :id AND status IN ('ROUTED');   -- the "from" statuses of Transition.ASSIGN
```

- **1 row changed:** this technician won. **0 rows:** someone else did, and the API answers 409 `already-taken`.
- Two of these on the same row can't both succeed. The second waits for the first's row lock; when the first commits, Postgres (READ COMMITTED) re-checks the WHERE clause against the committed row, finds `ASSIGNED`, and changes nothing.
- In the same transaction, the winner's offer becomes `ACCEPTED` (only if it's still open and not expired, otherwise everything rolls back) and every other offer for the job becomes `EXPIRED`.
- **The work order row is updated first.** Every technician accepting this job queues on that one row, so nobody holds an offer row while waiting for it, and the losers touch nothing else. Updating the offer first would let two technicians each hold their own offer while waiting for each other: a deadlock.
- `version + 1` keeps the ETag honest: a dispatcher's edit based on an older read now gets 412.
- The statement takes its statuses from the `Transition` table (ADR 0004), so the lifecycle stays defined in one place. It publishes the same `WorkOrderStatusChanged` event that `WorkOrder.assign()` would.
- **Accepting twice is safe for the winner.** A phone that lost the response retries; if the offer is already `ACCEPTED`, the API returns it again with 200.
- **Last line of defense:** a partial unique index allows at most one `ACCEPTED` offer per work order (V3), whatever the application code does.

How it runs: the technician belongs to no tenant. The request first reads the offer as the technician (row-level security shows technicians only their own offers, V7). Then the accept transaction runs as the work order's tenant, re-checking everything that matters inside its UPDATE statements.

## Alternatives considered

| Option | Verdict |
|---|---|
| **Optimistic locking** (`@Version`, retry on conflict) | Correct, but when 50 accept at once, 49 fail with an exception that has to be caught, the work order reloaded, and "already taken" worked out. More code for the same answer. It stays the tool for long edits, like a dispatcher's form (step 1.4). |
| **Pessimistic locking** (`SELECT ... FOR UPDATE`, check in Java, then write) | Correct, and the right tool when several reads and checks must happen before writing. Here the check is one condition, so the lock adds a round trip and holds the row longer for nothing. |
| **Distributed lock** (Redis) | Another system to run and to fail. A lock can expire during a GC pause while its holder still thinks it owns it, and the database write has to be correct anyway. Worth it only to protect something outside the database. |
| **`synchronized` / `ReentrantLock`** | Only coordinates threads in one JVM. With 3 instances, there are 3 independent locks. |

## Consequences

Good:
- One round trip decides the race, with no retry loop and no extra infrastructure, however many instances run.
- `FirstAcceptWinsTests` releases 50 virtual threads at once through a `CountDownLatch` against real Postgres: exactly 1 success, 49 × 409, and the database matches (one `ACCEPTED` offer, 49 `EXPIRED`, the work order assigned to the winner). It repeats 5 times per build.

Break-it results (what each layer catches):
- Replacing the conditional UPDATE with a check-then-act read and write still passed: the loser waits on the work order row, and by the time it gets there the winner has closed its offer, so the loser's `UPDATE offer ... WHERE status = 'OFFERED'` changes nothing and rolls everything back. The offer guard is a second conditional update.
- Breaking both: 4 of 5 runs failed, with Postgres rejecting 92 second `ACCEPTED` offers through the partial unique index. Only with all three layers gone does a job get two technicians.
- On a 4-core laptop, 50 virtual threads shared about two carrier threads and reached the database about 20 ms apart, which hid the race. The test uses 50 platform threads, and counts a crashed request as an answer instead of losing it with its thread.

Bad / accepted costs:
- Part of the rule lives in a query. `WorkOrder.assign()` still exists for in-memory work (tests, fixtures); the `Transition` table keeps the two in line.
- A bulk UPDATE bypasses Hibernate's in-memory copies, so the repository clears the persistence context afterwards.
- Offers expire lazily: an expired offer can't be accepted and no longer shows up, but its row stays `OFFERED` until the scheduled sweep that also returns unanswered work orders to PUBLISHED (step 2.5).
