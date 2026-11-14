# 6. Transactional outbox with a polling relay, before CDC

Date: 2026-11-14
Status: Accepted

## Context

Other systems (analytics, a future payment service, client webhooks) need to know when a work order changes. The change lives in PostgreSQL; the events go to Kafka. Writing to both is the **dual-write problem**: there's no transaction spanning PostgreSQL and Kafka, so any order of the two writes can go wrong.

- Save, then send: the app crashes (or Kafka is down) after the commit, and the event is lost. The work order says PUBLISHED; nobody downstream ever hears about it.
- Send, then save: the event is out, then the transaction rolls back. Downstream reacts to a change that never happened.

## Decision

- **Outbox table.** The workorder module appends a `work-order.status-changed` event to `outbox_event` from a synchronous listener, **inside the transaction that changes the work order**. They commit or roll back together. `Outbox.append` requires an active transaction (`MANDATORY`), so calling it outside one fails loudly.
- **Polling relay.** Every 500 ms, `OutboxRelay` reads up to 100 unsent events, oldest first, sends them to `dispatch.workorder.events.v1`, waits for Kafka's acknowledgement, and marks them published, all in one transaction. A failure rolls back, and the batch is sent again next time.
- **At least once.** A crash after Kafka acknowledged but before the "published" mark commits sends those events again. Every envelope carries an `eventId`, and consumers deduplicate on it (step 2.3).
- **Order per work order.** The message key is the work order id, so one work order's events land in one partition, sent in write order. The producer is idempotent (`acks=all`, `enable.idempotence=true`), so retries can't duplicate or reorder within a partition.
- **One relay at a time.** Each batch starts with `pg_try_advisory_xact_lock`. Another instance that doesn't get the lock skips the round instead of waiting. The query still uses `FOR UPDATE SKIP LOCKED`. On its own, `SKIP LOCKED` would let two instances relay different batches in parallel, and then events 50 and 150 of the same work order could leave in the wrong order. The advisory lock keeps order; `SKIP LOCKED` keeps the query safe if the relay is ever split by key.
- **Envelope.** `eventId`, `type`, `version`, `occurredAt`, `tenantId`, `aggregateId`, `payload`. The payload type (`WorkOrderStatusChangedV1`) is a public contract, separate from the internal event. Fields can be added; a breaking change gets version 2 on a new topic.
- Published rows are kept a week for debugging and replays, then deleted.

## Alternatives considered

| Option | Verdict |
|---|---|
| **CDC with Debezium** (read PostgreSQL's write-ahead log, publish to Kafka) | No polling, lowest latency, no relay code. But it adds Kafka Connect and Debezium to run and monitor, needs logical replication settings and slots that can fill the disk if the connector stops, and ties the event format to table columns. Worth it at high volume or when many tables must be streamed. The outbox table works with CDC too, so moving later only replaces the relay. |
| **Send to Kafka in an after-commit listener** | The dual-write problem again: lost on a crash or a Kafka outage between commit and send. |
| **Kafka transactions** | Atomic across Kafka topics, not across PostgreSQL and Kafka. Doesn't solve this. |
| **Spring Modulith event externalization** | Publishes events to Kafka from its registry, which is an outbox too. But it sends from async per-event listeners, so it doesn't promise per-key order, and the envelope and relay are less visible. Hand-written keeps every part explicit while learning. |

## Consequences

Good:
- An event exists if and only if its change committed. Kafka can be down for hours: requests keep succeeding, and the outbox drains when it's back (`OutboxRelayTests`, and the crash test in docs/failure-modes.md).
- Ordering per work order and at-least-once delivery are both testable.

Bad / accepted costs:
- Latency: up to the polling interval (500 ms) plus the send.
- One active relay caps throughput (about 100 events per round, several rounds per second while there's a backlog). That's far above Dispatch's needs; partitioning the relay by key, or CDC, is the upgrade.
- The table needs cleanup, and every write path pays for one more insert.
