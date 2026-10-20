-- The after-sales case an idempotent customer request opened or changed (docs/adr/0002-after-sales-state-machine.md
-- section 20). An order can have two customer requests (the second after withdrawing the first), so a replay finds
-- its case through the key's row, never through the order: a new key for the second request cannot answer with the
-- first case. NULL for every other request. The row is a short-lived replay record (app.cleanup.idempotency-retention),
-- so it goes with its case.
ALTER TABLE idempotency_requests
ADD COLUMN case_id BIGINT;

ALTER TABLE idempotency_requests
ADD CONSTRAINT fk_idempotency_after_sales_case FOREIGN KEY (case_id) REFERENCES after_sales_cases (id)
    ON DELETE CASCADE;
