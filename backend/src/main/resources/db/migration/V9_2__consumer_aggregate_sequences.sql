-- The last event sequence (outbox_events.aggregate_sequence, the envelope's "sequence") each consumer applied per
-- aggregate, for the consumers that keep per-aggregate state (merchant inbox, dispatch, timeout): an event whose
-- sequence is not above it is stale, because a later event of the aggregate was applied already (a late or replayed
-- dead letter), and is skipped. Written by EventSequenceTracker in the consumer's transaction, next to its
-- processed_events mark. Shared infrastructure like processed_events: a consumer only reads and writes its own rows
-- (consumer_name). Rows untouched for app.cleanup.processed-event-retention are deleted; an event older than that is
-- applied without the check, and the consumers' own guards (forward-only status moves, tombstones) still hold.
CREATE TABLE consumer_aggregate_sequences
(
    id             BIGSERIAL PRIMARY KEY NOT NULL,
    consumer_name  TEXT                  NOT NULL,
    aggregate_type TEXT                  NOT NULL,
    aggregate_id   BIGINT                NOT NULL,
    last_sequence  BIGINT                NOT NULL,
    updated_at     TIMESTAMP             NOT NULL,
    CONSTRAINT uq_consumer_aggregate_sequence UNIQUE (consumer_name, aggregate_type, aggregate_id)
);

-- The cleanup deletes by age in batches.
CREATE INDEX idx_consumer_aggregate_sequences_updated_at
ON consumer_aggregate_sequences (updated_at, id);
