CREATE INDEX idx_orders_customer_created_id
ON orders (customer_id, created_at, id);

CREATE INDEX idx_order_history_items_order_id_id
ON order_history_items (order_id, id);

CREATE INDEX idx_order_items_cart_id_id
ON order_items (cart_id, id);

CREATE INDEX idx_menu_items_restaurant_id_id
ON menu_items (restaurant_id, id);

CREATE INDEX idx_order_notifications_customer_created_id
ON order_notifications (customer_id, created_at, id);

ALTER TABLE carts
ADD CONSTRAINT chk_cart_total_price_non_negative CHECK (total_price >= 0);

ALTER TABLE menu_items
ADD CONSTRAINT chk_menu_item_price_non_negative CHECK (price >= 0);

ALTER TABLE order_items
ADD CONSTRAINT chk_order_item_price_non_negative CHECK (price >= 0);

ALTER TABLE order_items
ADD CONSTRAINT chk_order_item_quantity_positive CHECK (quantity > 0);

ALTER TABLE orders
ADD CONSTRAINT chk_order_total_price_non_negative CHECK (total_price >= 0);

ALTER TABLE order_history_items
ADD CONSTRAINT chk_order_history_item_price_non_negative CHECK (price >= 0);

ALTER TABLE order_history_items
ADD CONSTRAINT chk_order_history_item_quantity_positive CHECK (quantity > 0);
