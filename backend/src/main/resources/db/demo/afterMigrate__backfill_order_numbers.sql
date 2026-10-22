-- DEMO ONLY, loaded with the other db/demo callbacks (local/demo runs, the h2 profile and tests). Fills the public
-- order number into the merchant inbox rows and the deliveries that have none: rows from before V14, or that only
-- version 1 order events reached (docs/operations.md section 8). The application never reads orders from the
-- fulfilment modules, so this stays a seed-time repair; production uses the one-off statements in operations.md.
-- Idempotent: a row with a number is left alone, and a number never changes.
UPDATE merchant_orders
SET order_no = (SELECT o.order_no FROM orders o WHERE o.id = merchant_orders.order_id)
WHERE order_no IS NULL
  AND EXISTS (SELECT 1 FROM orders o WHERE o.id = merchant_orders.order_id AND o.order_no IS NOT NULL);

UPDATE deliveries
SET order_no = (SELECT o.order_no FROM orders o WHERE o.id = deliveries.order_id)
WHERE order_no IS NULL
  AND EXISTS (SELECT 1 FROM orders o WHERE o.id = deliveries.order_id AND o.order_no IS NOT NULL);
