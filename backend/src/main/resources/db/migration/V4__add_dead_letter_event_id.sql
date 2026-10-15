-- A dead letter is looked up by the event id of its envelope when the same event is dead-lettered again after a
-- replay, so the existing row is updated instead of the repeat being dropped. Rows stored before this migration
-- keep a NULL event_id; a repeat of one of those is recorded as a new row.
ALTER TABLE dead_letter_events
ADD COLUMN event_id TEXT;

CREATE INDEX idx_dead_letter_events_event_id
ON dead_letter_events (event_id);
