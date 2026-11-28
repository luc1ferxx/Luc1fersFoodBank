-- An optional stock limit per menu item (StockService). NULL means unlimited, so every existing item stays unlimited.
-- Checkout takes stock with a conditional UPDATE in the checkout's transaction and fails with 409 OUT_OF_STOCK when an
-- item is short; the check is the backstop that keeps stock from going below zero even if the code were wrong.
-- order_history_items.stock_reserved marks an order line that took stock: a cancel gives back only the marked lines and
-- clears the marks, so it gives back exactly what the order took, once, and an order from before this release gives
-- back nothing. Neither column is on an entity or a catalog DTO, so the cached menu does not change, and there is no
-- foreign key to menu_items (docs/adr/0001-order-sharding.md). The nullable column changes no existing row; the
-- constant default fills the existing order lines with FALSE without rewriting the table on PostgreSQL. Flyway holds
-- every lock until V15 commits, so keep menu_items first: a checkout reads menu_items before it inserts into
-- order_history_items, and the other order could deadlock with one (rollout: docs/operations.md section 14).
ALTER TABLE menu_items
ADD COLUMN stock_remaining INTEGER;

ALTER TABLE menu_items
ADD CONSTRAINT chk_menu_item_stock_remaining_non_negative CHECK (stock_remaining >= 0);

ALTER TABLE order_history_items
ADD COLUMN stock_reserved BOOLEAN DEFAULT FALSE NOT NULL;
