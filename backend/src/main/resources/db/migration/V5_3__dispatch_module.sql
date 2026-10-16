-- Dispatch module (com.laioffer.onlineorder.fulfilment.dispatch).
--
-- Simulated riders. active_deliveries is the rider's current load (deliveries ASSIGNED or PICKED_UP); the dispatch
-- consumer picks the least-loaded active rider with SELECT ... ORDER BY active_deliveries, id LIMIT 1
-- FOR UPDATE SKIP LOCKED and increments the load in the same transaction. Demo riders are seeded by the demo-only
-- Flyway callback in db/demo.
CREATE TABLE riders
(
    id                SERIAL PRIMARY KEY NOT NULL,
    name              TEXT               NOT NULL,
    phone             TEXT,
    active            BOOLEAN DEFAULT TRUE NOT NULL,
    active_deliveries INTEGER DEFAULT 0  NOT NULL,
    created_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT chk_rider_active_deliveries_non_negative CHECK (active_deliveries >= 0)
);


CREATE INDEX idx_riders_active_load_id
ON riders (active, active_deliveries, id);


-- One delivery per order (order_id is unique: a redelivered order.accepted cannot assign a second rider), with its
-- own status: ASSIGNED -> PICKED_UP -> DELIVERED, or CANCELLED when the order is cancelled before delivery. Built
-- from order events; no foreign key to orders, which the module does not own.
CREATE TABLE deliveries
(
    id            SERIAL PRIMARY KEY NOT NULL,
    order_id      INTEGER            NOT NULL,
    customer_id   INTEGER            NOT NULL,
    restaurant_id INTEGER            NOT NULL,
    rider_id      INTEGER            NOT NULL,
    status        TEXT               NOT NULL,
    assigned_at   TIMESTAMP          NOT NULL,
    picked_up_at  TIMESTAMP,
    delivered_at  TIMESTAMP,
    cancelled_at  TIMESTAMP,
    updated_at    TIMESTAMP          NOT NULL,
    CONSTRAINT uq_delivery_order UNIQUE (order_id),
    CONSTRAINT fk_delivery_rider FOREIGN KEY (rider_id) REFERENCES riders (id),
    CONSTRAINT chk_delivery_status CHECK (status IN ('ASSIGNED', 'PICKED_UP', 'DELIVERED', 'CANCELLED'))
);


CREATE INDEX idx_deliveries_rider_status
ON deliveries (rider_id, status);
