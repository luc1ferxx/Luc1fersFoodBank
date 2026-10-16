-- Order audit trail (order module, written by OrderService and checkout).
--
-- orders.updated_at: when the order last changed status. Existing orders get their created_at. Every status change
-- sets it in the same transaction as the guarded transition. The default only serves hand-written inserts; the
-- application always sets it. On PostgreSQL the UPDATE writes a new version of every order row; on a large table add
-- and backfill the column in batches ahead of the migration (IF NOT EXISTS and the IS NULL filter let it pick up from
-- there, see docs/operations.md section 7).
ALTER TABLE orders
ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP;

UPDATE orders
SET updated_at = created_at
WHERE updated_at IS NULL;

ALTER TABLE orders
ALTER COLUMN updated_at SET DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE orders
ALTER COLUMN updated_at SET NOT NULL;


-- One row per status change of an order, written in the transaction of the guarded transition (and of checkout, for
-- the creation: from_status NULL). Same-status no-ops and idempotent retries write nothing.
--
-- actor_type says who caused the change; actor_id is the customers.id of a CUSTOMER, ADMIN or MERCHANT actor, the
-- riders.id of a RIDER (the order module only learns the rider from the delivery event) and NULL for SYSTEM (timeouts,
-- the backfill below). reason is the cancel reason for cancellations (CUSTOMER_CANCELLED, MERCHANT_REJECTED: ...,
-- PAYMENT_TIMEOUT, ...) and DELIVERED for the steps a delivery completes. trace_id is the OpenTelemetry trace id of the
-- request or job that made the change, when one was in scope.
CREATE TABLE order_status_history
(
    id          BIGSERIAL PRIMARY KEY NOT NULL,
    order_id    BIGINT                NOT NULL,
    from_status TEXT,
    to_status   TEXT                  NOT NULL,
    actor_type  TEXT                  NOT NULL,
    actor_id    BIGINT,
    reason      TEXT,
    trace_id    TEXT,
    occurred_at TIMESTAMP             NOT NULL,
    CONSTRAINT fk_order_status_history_order FOREIGN KEY (order_id) REFERENCES orders (id) ON DELETE CASCADE,
    CONSTRAINT chk_order_status_history_from_status
        CHECK (from_status IS NULL OR from_status IN ('PLACED', 'PAID', 'ACCEPTED', 'PREPARING', 'COMPLETED', 'CANCELLED')),
    CONSTRAINT chk_order_status_history_to_status
        CHECK (to_status IN ('PLACED', 'PAID', 'ACCEPTED', 'PREPARING', 'COMPLETED', 'CANCELLED')),
    CONSTRAINT chk_order_status_history_actor_type
        CHECK (actor_type IN ('CUSTOMER', 'ADMIN', 'MERCHANT', 'RIDER', 'SYSTEM'))
);


-- Serves GET /orders/{orderId}/history (oldest first).
CREATE INDEX idx_order_status_history_order_id_id
ON order_status_history (order_id, id);


-- Orders placed before this migration get one row for the status they are in now. When they reached it is unknown,
-- so the row carries the order's created_at.
INSERT INTO order_status_history (order_id, from_status, to_status, actor_type, actor_id, reason, trace_id, occurred_at)
SELECT id, NULL, status, 'SYSTEM', NULL, 'backfill', NULL, created_at
FROM orders
ORDER BY id;
