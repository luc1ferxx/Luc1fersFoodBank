CREATE TABLE customers
(
    id                    SERIAL PRIMARY KEY   NOT NULL,
    email                 TEXT UNIQUE          NOT NULL,
    enabled               BOOLEAN DEFAULT TRUE NOT NULL,
    password              TEXT                 NOT NULL,
    first_name            TEXT,
    last_name             TEXT,
    account_status        TEXT DEFAULT 'ACTIVE' NOT NULL,
    email_verified        BOOLEAN DEFAULT TRUE NOT NULL,
    failed_login_attempts INTEGER DEFAULT 0    NOT NULL,
    locked_until          TIMESTAMP,
    last_login_at         TIMESTAMP,
    created_at            TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at            TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);


CREATE TABLE carts
(
    id           SERIAL PRIMARY KEY NOT NULL,
    customer_id  INTEGER UNIQUE     NOT NULL,
    total_price  NUMERIC            NOT NULL,
    CONSTRAINT fk_cart_customer FOREIGN KEY (customer_id) REFERENCES customers (id) ON DELETE CASCADE
);


CREATE TABLE restaurants
(
    id        SERIAL PRIMARY KEY NOT NULL,
    name      TEXT               NOT NULL,
    address   TEXT,
    image_url TEXT,
    phone     TEXT
);


CREATE TABLE menu_items
(
    id            SERIAL PRIMARY KEY NOT NULL,
    restaurant_id INTEGER            NOT NULL,
    name          TEXT               NOT NULL,
    price         NUMERIC            NOT NULL,
    description   TEXT,
    image_url     TEXT,
    CONSTRAINT fk_menu_restaurant FOREIGN KEY (restaurant_id) REFERENCES restaurants (id) ON DELETE CASCADE
);


CREATE TABLE order_items
(
    id           SERIAL PRIMARY KEY NOT NULL,
    menu_item_id INTEGER            NOT NULL,
    cart_id      INTEGER            NOT NULL,
    price        NUMERIC            NOT NULL,
    quantity     INTEGER            NOT NULL,
    CONSTRAINT uk_order_item_cart_menu UNIQUE (cart_id, menu_item_id),
    CONSTRAINT fk_order_item_cart FOREIGN KEY (cart_id) REFERENCES carts (id) ON DELETE CASCADE,
    CONSTRAINT fk_order_item_menu FOREIGN KEY (menu_item_id) REFERENCES menu_items (id) ON DELETE CASCADE
);


CREATE TABLE orders
(
    id          SERIAL PRIMARY KEY NOT NULL,
    customer_id INTEGER            NOT NULL,
    total_price NUMERIC            NOT NULL,
    status      TEXT               NOT NULL,
    created_at  TIMESTAMP          NOT NULL,
    CONSTRAINT fk_order_customer FOREIGN KEY (customer_id) REFERENCES customers (id) ON DELETE CASCADE,
    CONSTRAINT chk_order_status CHECK (status IN ('PLACED', 'PAID', 'ACCEPTED', 'PREPARING', 'COMPLETED', 'CANCELLED'))
);


CREATE TABLE order_history_items
(
    id                    SERIAL PRIMARY KEY NOT NULL,
    order_id              INTEGER            NOT NULL,
    menu_item_id          INTEGER            NOT NULL,
    restaurant_id         INTEGER            NOT NULL,
    price                 NUMERIC            NOT NULL,
    quantity              INTEGER            NOT NULL,
    menu_item_name        TEXT               NOT NULL,
    menu_item_description TEXT,
    menu_item_image_url   TEXT,
    CONSTRAINT fk_history_order FOREIGN KEY (order_id) REFERENCES orders (id) ON DELETE CASCADE
);


CREATE TABLE authorities
(
    id        SERIAL PRIMARY KEY NOT NULL,
    email     TEXT               NOT NULL,
    authority TEXT               NOT NULL,
    CONSTRAINT fk_authority_customer FOREIGN KEY (email) REFERENCES customers (email) ON DELETE CASCADE
);


CREATE TABLE outbox_events
(
    id             SERIAL PRIMARY KEY NOT NULL,
    aggregate_type TEXT               NOT NULL,
    aggregate_id   BIGINT             NOT NULL,
    event_id       TEXT               NOT NULL UNIQUE,
    topic          TEXT               NOT NULL,
    event_key      TEXT               NOT NULL,
    event_type     TEXT               NOT NULL,
    payload        TEXT               NOT NULL,
    status         TEXT               NOT NULL,
    attempts       INTEGER            NOT NULL DEFAULT 0,
    last_error     TEXT,
    created_at     TIMESTAMP          NOT NULL,
    updated_at     TIMESTAMP          NOT NULL,
    published_at   TIMESTAMP
);


CREATE TABLE processed_events
(
    id             SERIAL PRIMARY KEY NOT NULL,
    consumer_name  TEXT               NOT NULL,
    event_id       TEXT,
    dedup_key      TEXT               NOT NULL,
    event_type     TEXT               NOT NULL,
    aggregate_type TEXT,
    aggregate_id   BIGINT,
    processed_at   TIMESTAMP          NOT NULL,
    CONSTRAINT uq_processed_event_consumer_dedup UNIQUE (consumer_name, dedup_key)
);


CREATE TABLE order_notifications
(
    id          SERIAL PRIMARY KEY NOT NULL,
    order_id    INTEGER            NOT NULL,
    customer_id INTEGER            NOT NULL,
    event_type  TEXT               NOT NULL,
    title       TEXT               NOT NULL,
    message     TEXT               NOT NULL,
    created_at  TIMESTAMP          NOT NULL,
    CONSTRAINT fk_notification_order FOREIGN KEY (order_id) REFERENCES orders (id) ON DELETE CASCADE,
    CONSTRAINT fk_notification_customer FOREIGN KEY (customer_id) REFERENCES customers (id) ON DELETE CASCADE,
    CONSTRAINT uq_notification_order_event UNIQUE (order_id, event_type)
);


CREATE TABLE dead_letter_events
(
    id                SERIAL PRIMARY KEY NOT NULL,
    source_topic      TEXT,
    dead_letter_topic TEXT               NOT NULL,
    message_key       TEXT,
    payload           TEXT               NOT NULL,
    error_message     TEXT,
    replay_status     TEXT               NOT NULL,
    replay_attempts   INTEGER            NOT NULL DEFAULT 0,
    replayed_at       TIMESTAMP,
    last_replay_error TEXT,
    created_at        TIMESTAMP          NOT NULL,
    updated_at        TIMESTAMP          NOT NULL
);


CREATE TABLE idempotency_requests
(
    id              SERIAL PRIMARY KEY NOT NULL,
    customer_id     INTEGER            NOT NULL,
    scope           TEXT               NOT NULL,
    idempotency_key TEXT               NOT NULL,
    request_hash    TEXT               NOT NULL,
    status          TEXT               NOT NULL,
    order_id        INTEGER,
    created_at      TIMESTAMP          NOT NULL,
    updated_at      TIMESTAMP          NOT NULL,
    CONSTRAINT fk_idempotency_customer FOREIGN KEY (customer_id) REFERENCES customers (id) ON DELETE CASCADE,
    CONSTRAINT fk_idempotency_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT chk_idempotency_status CHECK (status IN ('PROCESSING', 'SUCCEEDED')),
    CONSTRAINT uq_idempotency_request UNIQUE (customer_id, scope, idempotency_key)
);


CREATE INDEX idx_outbox_events_status_updated_id
ON outbox_events (status, updated_at, id);


CREATE INDEX idx_dead_letter_events_replay_status
ON dead_letter_events (replay_status);
