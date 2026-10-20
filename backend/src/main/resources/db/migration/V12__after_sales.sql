-- After-sales refunds (docs/adr/0002-after-sales-state-machine.md). Every table here belongs to the order domain
-- (checkout and com.laioffer.onlineorder.aftersales) except merchant_after_sales_cases, the merchant module's
-- projection. The order-domain tables reference only orders and order_history_items (ADR 0001: no foreign keys to the
-- catalog, identity or module tables) and reach customer_id through their own column or their case. Money is
-- NUMERIC(12,2) with a currency next to it; the currency checks are V12_1, since the pattern match differs between
-- PostgreSQL and H2. Refunds are financial records: deleting an order that has a payment or a case, or a case that
-- has a refund, is refused (ON DELETE RESTRICT) instead of cascading.


-- The capture of a paid checkout (POST /payments/checkout), inserted in the transaction that creates the PAID order;
-- provider_reference is the (simulated) payment provider's id of the capture. A refund needs one and never exceeds its
-- amount. Orders checked out as PLACED and moved to PAID by an admin, and orders paid before this migration, have none
-- and get no automatic refund.
CREATE TABLE order_payments
(
    id                 BIGSERIAL PRIMARY KEY NOT NULL,
    order_id           BIGINT                NOT NULL,
    customer_id        BIGINT                NOT NULL,
    amount             NUMERIC(12,2)         NOT NULL,
    currency           CHAR(3)               NOT NULL,
    status             TEXT                  NOT NULL,
    provider_reference TEXT                  NOT NULL,
    captured_at        TIMESTAMP             NOT NULL,
    CONSTRAINT uq_order_payment_order UNIQUE (order_id),
    CONSTRAINT uq_order_payment_provider_reference UNIQUE (provider_reference),
    CONSTRAINT fk_order_payment_order FOREIGN KEY (order_id) REFERENCES orders (id) ON DELETE RESTRICT,
    CONSTRAINT chk_order_payment_amount CHECK (amount >= 0),
    CONSTRAINT chk_order_payment_status CHECK (status IN ('CAPTURED'))
);


-- A refund case of an order: a customer's request on a COMPLETED order (kind CUSTOMER_REQUEST) or the automatic refund
-- of a paid order that was cancelled (kind ORDER_CANCELLED, created straight in REFUNDING). A customer may request a
-- second time (attempt_no 2) only after withdrawing the first; an ORDER_CANCELLED case is always attempt 1. Completed
-- and cancelled orders exclude each other and a withdrawn case was never refunded, so at most one case of an order
-- reaches REFUNDING. version is the optimistic lock of every write (Spring Data JDBC @Version). deadline_at is the
-- deadline of the open status (PENDING_MERCHANT, COUNTER_OFFERED, REJECTED, IN_ARBITRATION) and NULL otherwise;
-- deadline_retry_at and deadline_attempts back off a deadline whose processing failed.
CREATE TABLE after_sales_cases
(
    id                BIGSERIAL PRIMARY KEY NOT NULL,
    order_id          BIGINT                NOT NULL,
    customer_id       BIGINT                NOT NULL,
    restaurant_id     BIGINT,
    kind              TEXT                  NOT NULL,
    attempt_no        SMALLINT              NOT NULL,
    status            TEXT                  NOT NULL,
    reason_code       TEXT,
    description       TEXT,
    order_total       NUMERIC(12,2)         NOT NULL,
    requested_amount  NUMERIC(12,2)         NOT NULL,
    offered_amount    NUMERIC(12,2),
    approved_amount   NUMERIC(12,2),
    refunded_amount   NUMERIC(12,2),
    currency          CHAR(3)               NOT NULL,
    merchant_decision TEXT,
    merchant_note     TEXT,
    resolution        TEXT,
    close_reason      TEXT,
    admin_note        TEXT,
    deadline_at       TIMESTAMP,
    deadline_retry_at TIMESTAMP,
    deadline_attempts INTEGER DEFAULT 0     NOT NULL,
    version           BIGINT                NOT NULL,
    created_at        TIMESTAMP             NOT NULL,
    updated_at        TIMESTAMP             NOT NULL,
    CONSTRAINT uq_after_sales_case_attempt UNIQUE (order_id, kind, attempt_no),
    CONSTRAINT uq_after_sales_case_approved UNIQUE (id, approved_amount, currency),
    CONSTRAINT fk_after_sales_case_order FOREIGN KEY (order_id) REFERENCES orders (id) ON DELETE RESTRICT,
    CONSTRAINT chk_after_sales_case_kind CHECK (kind IN ('CUSTOMER_REQUEST', 'ORDER_CANCELLED')),
    CONSTRAINT chk_after_sales_case_attempt CHECK (attempt_no IN (1, 2)
        AND (kind = 'CUSTOMER_REQUEST' OR attempt_no = 1)),
    CONSTRAINT chk_after_sales_case_status CHECK (status IN ('PENDING_MERCHANT', 'COUNTER_OFFERED', 'REJECTED',
        'IN_ARBITRATION', 'REFUNDING', 'REFUNDED', 'CLOSED')),
    CONSTRAINT chk_after_sales_case_request CHECK (kind = 'ORDER_CANCELLED'
        OR (restaurant_id IS NOT NULL AND reason_code IS NOT NULL AND description IS NOT NULL)),
    CONSTRAINT chk_after_sales_case_reason CHECK (reason_code IS NULL OR reason_code IN ('MISSING_ITEM', 'WRONG_ITEM',
        'QUALITY_ISSUE', 'DAMAGED_PACKAGING', 'FOOD_SAFETY', 'LATE_DELIVERY', 'OTHER')),
    CONSTRAINT chk_after_sales_case_amounts CHECK (order_total >= 0 AND requested_amount > 0
        AND requested_amount <= order_total
        AND (offered_amount IS NULL OR (offered_amount > 0 AND offered_amount < requested_amount))
        AND (approved_amount IS NULL OR (approved_amount > 0 AND approved_amount <= requested_amount))
        AND (refunded_amount IS NULL OR refunded_amount = approved_amount)),
    CONSTRAINT chk_after_sales_case_money_state CHECK (status NOT IN ('REFUNDING', 'REFUNDED')
        OR approved_amount IS NOT NULL),
    CONSTRAINT chk_after_sales_case_refunded CHECK ((status = 'REFUNDED' AND refunded_amount IS NOT NULL)
        OR (status <> 'REFUNDED' AND refunded_amount IS NULL)),
    CONSTRAINT chk_after_sales_case_deadline CHECK (
        (status IN ('PENDING_MERCHANT', 'COUNTER_OFFERED', 'REJECTED', 'IN_ARBITRATION') AND deadline_at IS NOT NULL)
        OR (status IN ('REFUNDING', 'REFUNDED', 'CLOSED') AND deadline_at IS NULL)),
    CONSTRAINT chk_after_sales_case_merchant_decision CHECK (merchant_decision IS NULL
        OR merchant_decision IN ('APPROVED', 'REJECTED', 'OFFERED')),
    CONSTRAINT chk_after_sales_case_resolution CHECK (resolution IS NULL OR resolution IN ('MERCHANT_APPROVED',
        'MERCHANT_TIMEOUT', 'OFFER_ACCEPTED', 'OFFER_TIMEOUT', 'ARBITRATION_FULL', 'ARBITRATION_PARTIAL',
        'ARBITRATION_TIMEOUT', 'ORDER_CANCELLED')),
    CONSTRAINT chk_after_sales_case_close_reason CHECK (close_reason IS NULL
        OR close_reason IN ('WITHDRAWN', 'CUSTOMER_TIMEOUT', 'ARBITRATION_REJECTED')),
    CONSTRAINT chk_after_sales_case_closed CHECK ((status = 'CLOSED' AND close_reason IS NOT NULL)
        OR (status <> 'CLOSED' AND close_reason IS NULL))
);

-- The customer's cases newest first, the admin queues by status, and the deadline poller (oldest due first). Lookups
-- by order use uq_after_sales_case_attempt.
CREATE INDEX idx_after_sales_cases_customer_created
ON after_sales_cases (customer_id, created_at, id);

CREATE INDEX idx_after_sales_cases_status_created
ON after_sales_cases (status, created_at, id);

CREATE INDEX idx_after_sales_cases_deadline
ON after_sales_cases (deadline_at, id);


-- The order lines a case asks a refund for, with the quantity asked (an ORDER_CANCELLED case lists every line in full).
-- unit_price and menu_item_name are copied from the order line; amount is unit_price x quantity.
CREATE TABLE after_sales_case_lines
(
    id             BIGSERIAL PRIMARY KEY NOT NULL,
    case_id        BIGINT                NOT NULL,
    order_item_id  BIGINT                NOT NULL,
    menu_item_name TEXT                  NOT NULL,
    unit_price     NUMERIC(12,2)         NOT NULL,
    quantity       INTEGER               NOT NULL,
    amount         NUMERIC(12,2)         NOT NULL,
    currency       CHAR(3)               NOT NULL,
    CONSTRAINT uq_after_sales_line_item UNIQUE (case_id, order_item_id),
    CONSTRAINT fk_after_sales_line_case FOREIGN KEY (case_id) REFERENCES after_sales_cases (id) ON DELETE CASCADE,
    CONSTRAINT fk_after_sales_line_order_item FOREIGN KEY (order_item_id) REFERENCES order_history_items (id)
        ON DELETE CASCADE,
    CONSTRAINT chk_after_sales_line_quantity CHECK (quantity > 0),
    CONSTRAINT chk_after_sales_line_amounts CHECK (unit_price >= 0 AND amount >= 0 AND amount = unit_price * quantity)
);


-- Every step of a case (its opening included), with the actor that took it: an OrderActor type and id. Append-only and
-- low volume, so deliberately not partitioned (AGENTS.md partitions the high-volume tables only).
CREATE TABLE after_sales_case_history
(
    id          BIGSERIAL PRIMARY KEY NOT NULL,
    case_id     BIGINT                NOT NULL,
    from_status TEXT,
    to_status   TEXT                  NOT NULL,
    action      TEXT                  NOT NULL,
    actor_type  TEXT                  NOT NULL,
    actor_id    BIGINT,
    amount      NUMERIC(12,2),
    currency    CHAR(3)               NOT NULL,
    note        TEXT,
    trace_id    TEXT,
    occurred_at TIMESTAMP             NOT NULL,
    CONSTRAINT fk_after_sales_history_case FOREIGN KEY (case_id) REFERENCES after_sales_cases (id) ON DELETE CASCADE,
    CONSTRAINT chk_after_sales_history_actor CHECK (actor_type IN ('CUSTOMER', 'ADMIN', 'MERCHANT', 'RIDER', 'SYSTEM')),
    CONSTRAINT chk_after_sales_history_amount CHECK (amount IS NULL OR amount >= 0)
);

CREATE INDEX idx_after_sales_case_history_case
ON after_sales_case_history (case_id, occurred_at, id);


-- The refund of a case that reached REFUNDING, executed against the payment gateway by the after-sales poller: claimed
-- with a lease (PROCESSING until claimed_until), retried with backoff (PENDING until next_attempt_at), FAILED for an
-- admin to retry. gateway_key is the gateway's idempotency key; payment_reference is the capture it refunds
-- (order_payments.provider_reference). One refund per case and per order, and the composite key ties its amount and
-- currency to the case's approved amount, so an order is never refunded more than its case approved.
CREATE TABLE refunds
(
    id                 BIGSERIAL PRIMARY KEY NOT NULL,
    case_id            BIGINT                NOT NULL,
    order_id           BIGINT                NOT NULL,
    customer_id        BIGINT                NOT NULL,
    gateway_key        TEXT                  NOT NULL,
    payment_reference  TEXT                  NOT NULL,
    amount             NUMERIC(12,2)         NOT NULL,
    currency           CHAR(3)               NOT NULL,
    status             TEXT                  NOT NULL,
    attempts           INTEGER DEFAULT 0     NOT NULL,
    next_attempt_at    TIMESTAMP,
    claimed_until      TIMESTAMP,
    provider_reference TEXT,
    last_error         TEXT,
    version            BIGINT                NOT NULL,
    created_at         TIMESTAMP             NOT NULL,
    updated_at         TIMESTAMP             NOT NULL,
    succeeded_at       TIMESTAMP,
    CONSTRAINT uq_refund_case UNIQUE (case_id),
    CONSTRAINT uq_refund_order UNIQUE (order_id),
    CONSTRAINT uq_refund_gateway_key UNIQUE (gateway_key),
    CONSTRAINT fk_refund_case_approved FOREIGN KEY (case_id, amount, currency)
        REFERENCES after_sales_cases (id, approved_amount, currency) ON DELETE RESTRICT,
    CONSTRAINT chk_refund_status CHECK (status IN ('PENDING', 'PROCESSING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT chk_refund_amount CHECK (amount > 0),
    CONSTRAINT chk_refund_attempts CHECK (attempts >= 0),
    CONSTRAINT chk_refund_pending CHECK (status <> 'PENDING' OR next_attempt_at IS NOT NULL),
    CONSTRAINT chk_refund_processing CHECK (status <> 'PROCESSING' OR claimed_until IS NOT NULL),
    CONSTRAINT chk_refund_succeeded CHECK (
        (status = 'SUCCEEDED' AND provider_reference IS NOT NULL AND succeeded_at IS NOT NULL)
        OR (status <> 'SUCCEEDED' AND succeeded_at IS NULL))
);

-- The poller's claim: due PENDING refunds and PROCESSING refunds whose lease expired. Plain composite indexes, which
-- H2 builds too (no partial index needed).
CREATE INDEX idx_refunds_due
ON refunds (status, next_attempt_at, id);

CREATE INDEX idx_refunds_claim_expiry
ON refunds (status, claimed_until, id);


-- Merchant module (com.laioffer.onlineorder.fulfilment.merchant): the merchant's after-sales inbox, built from
-- after-sales-events by its own consumer group (CUSTOMER_REQUEST cases only). No foreign keys: the module never reads
-- the order domain's tables. lines is a JSON snapshot of the case lines; case_version is the case's version when the
-- row was last written, so that a late or replayed event never moves a row back.
CREATE TABLE merchant_after_sales_cases
(
    id                BIGSERIAL PRIMARY KEY NOT NULL,
    case_id           BIGINT                NOT NULL,
    order_id          BIGINT                NOT NULL,
    order_no          BIGINT,
    restaurant_id     BIGINT                NOT NULL,
    customer_id       BIGINT                NOT NULL,
    status            TEXT                  NOT NULL,
    reason_code       TEXT                  NOT NULL,
    description       TEXT                  NOT NULL,
    lines             TEXT                  NOT NULL,
    requested_amount  NUMERIC(12,2)         NOT NULL,
    offered_amount    NUMERIC(12,2),
    approved_amount   NUMERIC(12,2),
    currency          CHAR(3)               NOT NULL,
    merchant_decision TEXT,
    merchant_note     TEXT,
    deadline_at       TIMESTAMP,
    case_version      BIGINT                NOT NULL,
    opened_at         TIMESTAMP             NOT NULL,
    updated_at        TIMESTAMP             NOT NULL,
    CONSTRAINT uq_merchant_after_sales_case UNIQUE (case_id),
    CONSTRAINT chk_merchant_after_sales_status CHECK (status IN ('PENDING_MERCHANT', 'COUNTER_OFFERED', 'REJECTED',
        'IN_ARBITRATION', 'REFUNDING', 'REFUNDED', 'CLOSED')),
    CONSTRAINT chk_merchant_after_sales_amounts CHECK (requested_amount >= 0
        AND (offered_amount IS NULL OR offered_amount >= 0) AND (approved_amount IS NULL OR approved_amount >= 0))
);

CREATE INDEX idx_merchant_after_sales_restaurant_status
ON merchant_after_sales_cases (restaurant_id, status, opened_at, id);

CREATE INDEX idx_merchant_after_sales_restaurant
ON merchant_after_sales_cases (restaurant_id, opened_at, id);


-- Notifications are unique per order, event type and dedup_scope. Order and delivery events keep the empty scope, so
-- they deduplicate exactly as before; after-sales notifications are scoped to their case ('case:' || case id), since
-- the two cases an order can have send the same event types. The new key is added before the old one goes, so the
-- table is never without one.
ALTER TABLE order_notifications
ADD COLUMN dedup_scope TEXT DEFAULT '' NOT NULL;

ALTER TABLE order_notifications
ADD CONSTRAINT uq_notification_order_event_scope UNIQUE (order_id, event_type, dedup_scope);

ALTER TABLE order_notifications
DROP CONSTRAINT uq_notification_order_event;
