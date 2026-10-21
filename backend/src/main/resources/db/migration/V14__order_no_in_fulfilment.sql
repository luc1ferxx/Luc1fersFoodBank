-- The order's public number in the merchant inbox and in the deliveries, so the merchant and rider consoles can show
-- it instead of the internal order id (docs/operations.md section 8, "Order events v2 and V14"). The consumers copy it
-- from data.order_no of the order events (version 2) and fill it into a row that has none from any later or replayed
-- event that carries it; it is never replaced, since an order's number never changes. NULL for rows written from
-- version 1 events only, rows from before this release included: the consoles show the order id for those. A copy of
-- orders.order_no without a foreign key or a unique constraint, like order_id itself (modules share no keys).
-- dispatch_orders needs none: a delivery is only created while order.accepted is handled, which carries the number.
-- Nullable columns without a default change no existing row (catalog only on PostgreSQL).
ALTER TABLE merchant_orders
ADD COLUMN order_no BIGINT;

ALTER TABLE deliveries
ADD COLUMN order_no BIGINT;
