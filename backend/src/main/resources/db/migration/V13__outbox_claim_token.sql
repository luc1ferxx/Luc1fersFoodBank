-- Claim fencing for the outbox publisher (docs/operations.md section 8). Each claim writes a token of its own, and
-- the publisher's result writes (published, released, retry, failed) change a row only while it is still PROCESSING
-- under that token. A publisher whose claim expired mid-batch can then no longer overwrite what the publisher that
-- reclaimed the event wrote. Null while no claim of a current publisher holds the row.
ALTER TABLE outbox_events ADD COLUMN claim_token VARCHAR(36);

-- The statuses the code writes: OutboxStatus, and the replay statuses of DeadLetterReplayService. On PostgreSQL each
-- ADD CONSTRAINT checks the existing rows while it holds the table's ACCESS EXCLUSIVE lock, so outbox writes
-- (checkouts, order and delivery changes) wait for the scan of outbox_events (published rows are kept for
-- app.cleanup.outbox-retention, 7 days by default); docs/operations.md section 8 has the numbers.
ALTER TABLE outbox_events
    ADD CONSTRAINT chk_outbox_events_status
        CHECK (status IN ('PENDING', 'PROCESSING', 'PUBLISHED', 'FAILED', 'ABANDONED'));

ALTER TABLE dead_letter_events
    ADD CONSTRAINT chk_dead_letter_events_replay_status
        CHECK (replay_status IN ('PENDING', 'REPLAYING', 'REPLAYED', 'REPLAY_FAILED'));
