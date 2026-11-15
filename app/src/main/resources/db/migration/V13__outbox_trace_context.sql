-- Tracing (step 2.7, T25): the W3C traceparent of the request that wrote the event. The relay sends the event
-- later, from another thread; with this it continues the request's trace, so one trace shows the request, the
-- relay, Kafka and the consumers.
ALTER TABLE outbox_event ADD COLUMN trace_parent TEXT;
