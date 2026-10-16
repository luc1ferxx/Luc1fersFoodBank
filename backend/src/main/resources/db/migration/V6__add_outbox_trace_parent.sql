-- W3C traceparent of the request (or job) that wrote the outbox event, so the publisher can continue that trace
-- when it relays the event to Kafka. Nullable: rows written before this migration and rows written outside a traced
-- context have none, and the publisher then starts a new trace. Adding a nullable column without a default is a
-- catalog-only change on PostgreSQL and does not rewrite the table.
ALTER TABLE outbox_events
ADD COLUMN trace_parent TEXT;
