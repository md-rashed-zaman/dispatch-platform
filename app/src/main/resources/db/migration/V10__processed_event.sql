-- Idempotent consumers (step 2.3, T19). Kafka delivers at least once, so a consumer can see the same event
-- twice: after a crash before its offset was committed, after a rebalance, or when the outbox relay resends.
-- A consumer inserts (consumer, event_id) in the same transaction as its work; the second delivery finds the
-- row and skips. If the work fails, the insert rolls back with it, so a retry isn't mistaken for a duplicate.
-- Not tenant-owned (consumers serve every tenant), so no row-level security, like outbox_event.
CREATE TABLE processed_event
(
  consumer     TEXT        NOT NULL,             -- each consumer group deduplicates on its own
  event_id     UUID        NOT NULL,
  processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (consumer, event_id)
);

-- Cleanup of entries older than Kafka's retention: an event that old can't be delivered again.
CREATE INDEX processed_event_processed_at_idx ON processed_event (processed_at);
