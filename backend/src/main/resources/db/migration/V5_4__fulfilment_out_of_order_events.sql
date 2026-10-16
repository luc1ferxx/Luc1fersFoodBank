-- Order events that reach a fulfilment consumer out of order: an event dead-lettered from one consumer group and
-- replayed after later events of the same order were applied there (see README "Order fulfilment").


-- Merchant inbox. Any order event (order.accepted, order.cancelled, ...) that reaches the merchant group before
-- order.paid now creates the inbox row itself, with the status that event reports, so a replayed order.paid can no
-- longer add a PAID row for an order that is already cancelled. Such a row has no paid_at until order.paid arrives,
-- and the merchant only sees (and acts on) rows with a paid_at.
ALTER TABLE merchant_orders
ALTER COLUMN paid_at DROP NOT NULL;


-- Dispatch module: one row per order the dispatch consumer handled an order.accepted or order.cancelled for. Both
-- handlers create the row if absent and lock it before they touch deliveries or riders, so the two serialize per order
-- even when they run on different consumers. cancelled_at is the tombstone: order.cancelled sets it, and an
-- order.accepted handled afterwards (late or replayed) assigns no rider. No foreign key to orders, which the module
-- does not own.
CREATE TABLE dispatch_orders
(
    order_id     INTEGER PRIMARY KEY NOT NULL,
    cancelled_at TIMESTAMP,
    created_at   TIMESTAMP           NOT NULL,
    updated_at   TIMESTAMP           NOT NULL
);
