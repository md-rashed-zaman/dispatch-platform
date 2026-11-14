-- Transactional outbox (step 2.2, T17, ADR 0006): events are written here in the same transaction as the
-- change they describe, and a relay sends them to Kafka afterwards.
-- Not tenant-owned: the relay serves every tenant, so there's no row-level security on this table. The
-- tenant is inside each event's envelope.
CREATE TABLE outbox_event
(
  -- Write order. The relay sends oldest first, so one work order's events keep their order.
  id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  event_id     UUID        NOT NULL UNIQUE,      -- consumers deduplicate on it
  topic        TEXT        NOT NULL,
  message_key  TEXT        NOT NULL,             -- Kafka partition key: the aggregate id
  event_type   TEXT        NOT NULL,
  payload      JSONB       NOT NULL,             -- the whole envelope, exactly as sent
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  published_at TIMESTAMPTZ
);

-- The relay only ever looks for unsent events, oldest first; a partial index stays tiny.
CREATE INDEX outbox_event_unpublished_idx ON outbox_event (id) WHERE published_at IS NULL;
-- Cleanup of events sent more than a week ago.
CREATE INDEX outbox_event_published_idx ON outbox_event (published_at) WHERE published_at IS NOT NULL;
