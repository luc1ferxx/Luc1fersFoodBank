-- Timeout module (com.laioffer.onlineorder.fulfilment.timeout).
--
-- Pending order deadlines, written by the timeout consumer group: a PAYMENT deadline when an order is placed
-- (order.created) and an ACCEPT deadline when it is paid (order.paid). A row only lives while its deadline is
-- pending: later order events delete it, and the poller deletes it once it fired. Kafka has no delayed delivery,
-- so the deadline is kept here and polled (see README "Order fulfilment").
--
-- The poller claims due rows with SELECT ... WHERE due_at <= now ORDER BY due_at FOR UPDATE SKIP LOCKED, served by
-- the due_at index; (order_id, deadline_type) is unique, so a redelivered event cannot add a second deadline.
-- No foreign key to orders: the module does not own that table.
CREATE TABLE order_deadlines
(
    id            SERIAL PRIMARY KEY NOT NULL,
    order_id      INTEGER            NOT NULL,
    deadline_type TEXT               NOT NULL,
    due_at        TIMESTAMP          NOT NULL,
    attempts      INTEGER DEFAULT 0  NOT NULL,
    last_error    TEXT,
    created_at    TIMESTAMP          NOT NULL,
    updated_at    TIMESTAMP          NOT NULL,
    CONSTRAINT uq_order_deadline_order_type UNIQUE (order_id, deadline_type),
    CONSTRAINT chk_order_deadline_type CHECK (deadline_type IN ('PAYMENT', 'ACCEPT'))
);


CREATE INDEX idx_order_deadlines_due_at_id
ON order_deadlines (due_at, id);
