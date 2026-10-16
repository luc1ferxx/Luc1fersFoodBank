-- Merchant module (com.laioffer.onlineorder.fulfilment.merchant).
--
-- A merchant is a login (a customers row with ROLE_MERCHANT) bound to exactly one restaurant.
CREATE TABLE merchant_accounts
(
    id            SERIAL PRIMARY KEY NOT NULL,
    customer_id   INTEGER            NOT NULL,
    restaurant_id INTEGER            NOT NULL,
    created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT uq_merchant_account_customer UNIQUE (customer_id),
    CONSTRAINT fk_merchant_account_customer FOREIGN KEY (customer_id) REFERENCES customers (id) ON DELETE CASCADE,
    CONSTRAINT fk_merchant_account_restaurant FOREIGN KEY (restaurant_id) REFERENCES restaurants (id) ON DELETE CASCADE
);


CREATE INDEX idx_merchant_accounts_restaurant_id
ON merchant_accounts (restaurant_id);


-- The merchant's order inbox, built from order events by the merchant consumer group: a row is added on
-- order.paid and its status follows the later order events. The module owns this table and never reads the orders
-- table, so there is deliberately no foreign key to orders. order_id is unique: a redelivered order.paid cannot
-- add a second row. items is a JSON snapshot of the order lines.
CREATE TABLE merchant_orders
(
    id            SERIAL PRIMARY KEY NOT NULL,
    order_id      INTEGER            NOT NULL,
    restaurant_id INTEGER            NOT NULL,
    customer_id   INTEGER            NOT NULL,
    status        TEXT               NOT NULL,
    total_price   NUMERIC            NOT NULL,
    items         TEXT               NOT NULL,
    cancel_reason TEXT,
    paid_at       TIMESTAMP          NOT NULL,
    created_at    TIMESTAMP          NOT NULL,
    updated_at    TIMESTAMP          NOT NULL,
    CONSTRAINT uq_merchant_order_order UNIQUE (order_id),
    CONSTRAINT chk_merchant_order_status CHECK (status IN ('PAID', 'ACCEPTED', 'PREPARING', 'COMPLETED', 'CANCELLED'))
);


CREATE INDEX idx_merchant_orders_restaurant_status_paid_id
ON merchant_orders (restaurant_id, status, paid_at, id);

CREATE INDEX idx_merchant_orders_restaurant_paid_id
ON merchant_orders (restaurant_id, paid_at, id);
