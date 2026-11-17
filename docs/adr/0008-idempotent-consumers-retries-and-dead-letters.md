# 8. Idempotent Kafka consumers: a processed-event table, short in-place retries, a dead-letter topic per consumer

Date: 2026-11-15
Status: Accepted

## Context

Kafka delivers at least once: a consumer sees an event again after a crash before its offset commit, after a rebalance, or when the outbox relay resends a batch (ADR 0006). Some events can never be processed (malformed JSON, an unknown version), and some fail for a while (a dependency down). The notification consumer sends emails, which mustn't go out twice and mustn't be lost.

## Decision

- **Deduplicate in the same transaction as the work.** `processed_event (consumer, event_id)` is inserted with `on conflict do nothing`; if a row was inserted, the work runs (queue the email) in the same transaction. A failure rolls both back, so the retry isn't mistaken for a duplicate.
- **Retries in place, short.** `DefaultErrorHandler` with exponential backoff: 1, 2, 4, 8 s, then give up. While it retries, that partition waits, which keeps one work order's events in order.
- **Poison messages skip the retries.** `MalformedEventException` (not JSON, missing fields, unknown version) is not retryable: straight to the dead-letter topic. Unknown event *types* are ignored, so producers can add types.
- **One dead-letter topic per consumer:** `dispatch.workorder.events.v1.notification-dlt`, not spring-kafka's shared `<topic>-dlt`, so each consumer's failures are its own to inspect and replay. Same partition count as the source. Each dead letter is logged at ERROR.
- **The work is a database write, not the email itself:** the consumer queues `outgoing_email`, and a separate sender delivers it (T20). An email crash between "sent" and "marked sent" can still send twice; the event id is the idempotency key to give a real provider.

## Alternatives considered

| Option | Verdict |
|---|---|
| Rely on Kafka exactly-once (transactions) | Covers Kafka-to-Kafka, not Kafka to a database plus email. |
| Deduplicate in a separate transaction before the work | A failed send then looks processed: the email is lost. Shown by break-it. |
| Non-blocking retries (`@RetryableTopic`, retry topics) | The partition keeps flowing, but later events for the same work order can overtake a failing one. Order matters for a lifecycle. |
| Retry poison messages too | Can't succeed, and blocks the partition for the whole backoff. |
| Shared `-dlt` topic | With a second consumer group, failures mix and replays hit consumers that already succeeded. |

## Consequences

Good: duplicates are harmless; a broken message costs one DLT entry, not a stuck partition; a short outage costs a few seconds of delay. All proven in `CompanyNotificationsTests` with a real Kafka, including break-it runs.

Bad / accepted: `processed_event` grows (deleted after two weeks, longer than Kafka keeps events); blocking retries hold up one partition for up to about 15 s; DLT replay is manual.
