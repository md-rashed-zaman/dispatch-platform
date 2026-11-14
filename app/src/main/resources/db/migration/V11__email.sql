-- Email notifications (step 2.4, T20).

-- Where a company wants its notifications. Optional: without it, the company gets none.
ALTER TABLE company ADD COLUMN notification_email TEXT CHECK (notification_email LIKE '%_@_%');

-- Emails waiting to be sent. Notifications queue a row in the same transaction as their work, and a sender
-- job delivers it, so a slow or broken mail server never slows down anything else, and nothing is lost
-- while it's down: the row waits and is tried again with growing pauses.
-- Not tenant-owned (the sender serves every tenant), so no row-level security, like outbox_event.
CREATE TABLE outgoing_email
(
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  message_id      UUID        NOT NULL UNIQUE,      -- the event it's about: queueing twice does nothing
  recipient       TEXT        NOT NULL,
  subject         TEXT        NOT NULL,
  text_body       TEXT        NOT NULL,
  html_body       TEXT        NOT NULL,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  attempts        INT         NOT NULL DEFAULT 0,
  next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_error      TEXT,
  sent_at         TIMESTAMPTZ,
  failed_at       TIMESTAMPTZ,                      -- gave up after the last attempt
  CHECK (sent_at IS NULL OR failed_at IS NULL)
);

-- The sender only looks for emails that are due; sent and failed ones drop out of the index.
CREATE INDEX outgoing_email_due_idx ON outgoing_email (next_attempt_at) WHERE sent_at IS NULL AND failed_at IS NULL;
