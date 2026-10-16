-- An order now belongs to exactly one restaurant: a cart only holds items of one restaurant and checkout records
-- it here, so the merchant of that restaurant can accept or reject the order. Orders placed before this migration
-- keep a NULL restaurant_id and stay on the admin-driven flow (PATCH /orders/{id}/status).
--
-- No foreign key to restaurants: an order is history and must survive the removal of its restaurant, the same as
-- order_history_items.restaurant_id.
ALTER TABLE orders
ADD COLUMN restaurant_id INTEGER;

-- Why an order was cancelled, for example MERCHANT_TIMEOUT, PAYMENT_TIMEOUT, MERCHANT_REJECTED: <note>,
-- CUSTOMER_CANCELLED. NULL for orders that are not cancelled and for orders cancelled before this migration.
ALTER TABLE orders
ADD COLUMN cancel_reason TEXT;

-- Serves per-restaurant order lookups. A plain CREATE INDEX inside Flyway's transaction (see V3 for why not
-- CONCURRENTLY); it blocks order writes for the length of the build.
CREATE INDEX idx_orders_restaurant_created_id
ON orders (restaurant_id, created_at, id);
