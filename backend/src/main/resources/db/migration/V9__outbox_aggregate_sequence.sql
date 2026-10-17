-- Per-aggregate event order (README "Event contracts", "Outbox and Kafka").
--
-- outbox_events.aggregate_sequence: the position of an event among the events of its aggregate (aggregate_type,
-- aggregate_id), 1, 2, 3, ... in commit order, also carried in the event envelope as "sequence". The publisher only
-- claims an event when no earlier event of its aggregate is still PENDING, PROCESSING or FAILED (the index for that is
-- V9_1), and consumers that keep per-aggregate state skip an event that is not newer than the last one they applied
-- (V9_2). Nullable, without a default, so adding it is a catalog-only change on PostgreSQL: rows written before this
-- migration that are already PUBLISHED keep NULL and are deleted by the outbox cleanup after its retention.
ALTER TABLE outbox_events
ADD COLUMN aggregate_sequence BIGINT;


-- The last sequence handed out per aggregate. OrderEventOutboxService.enqueueEvent steps the aggregate's row in the
-- transaction that writes the event; the row lock it takes makes two transactions writing events of the same aggregate
-- take turns, so sequences are unique, gapless (a rollback gives its step back) and in commit order, whatever the
-- aggregate type. A separate table rather than MAX(aggregate_sequence) of the outbox, because published outbox rows are
-- deleted after app.cleanup.outbox-retention: a counter derived from what is left would start again at 1, and every
-- consumer that remembers a higher sequence for the aggregate would skip the new events as stale. Rows are kept for good
-- (one per order, delivery and merchant order).
CREATE TABLE outbox_aggregate_sequences
(
    id             BIGSERIAL PRIMARY KEY NOT NULL,
    aggregate_type TEXT                  NOT NULL,
    aggregate_id   BIGINT                NOT NULL,
    last_sequence  BIGINT                NOT NULL,
    CONSTRAINT uq_outbox_aggregate_sequence UNIQUE (aggregate_type, aggregate_id)
);


-- Number the events that are still waiting (PENDING, PROCESSING or FAILED) in id order per aggregate, so the head-of-line
-- claim keeps their order from the first run, and start every counter after the last number given out. Published rows
-- are left alone: nothing reads their order any more, and rewriting them would rewrite most of the table. Usually the
-- backlog is a handful of rows; MERGE (PostgreSQL 15+, H2) numbers it in one pass.
MERGE INTO outbox_events target
USING (
    SELECT id,
           ROW_NUMBER() OVER (PARTITION BY aggregate_type, aggregate_id ORDER BY id) AS sequence_number
    FROM outbox_events
    WHERE status IN ('PENDING', 'PROCESSING', 'FAILED')
) numbered
ON target.id = numbered.id
WHEN MATCHED THEN
    UPDATE SET aggregate_sequence = numbered.sequence_number;

INSERT INTO outbox_aggregate_sequences (aggregate_type, aggregate_id, last_sequence)
SELECT aggregate_type, aggregate_id, MAX(aggregate_sequence)
FROM outbox_events
WHERE aggregate_sequence IS NOT NULL
GROUP BY aggregate_type, aggregate_id;
