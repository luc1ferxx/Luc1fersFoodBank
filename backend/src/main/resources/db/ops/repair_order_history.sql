-- Repairs the order history after a rollout of V7_1 during which pods of an older version kept running, for example
-- a rolling update (docs/operations.md section 7). Those pods change order status without an order_status_history
-- row and without moving orders.updated_at, and the orders they create get no history at all. What they did and when
-- is lost; this records each order's current status as a SYSTEM 'backfill' row, the way V7_1 did for the orders that
-- existed when it ran.
--
-- Not a Flyway migration (nothing runs it automatically). PostgreSQL only. Idempotent: a second run changes nothing.
-- Run it after the rollout has finished, as one transaction, with the session in UTC like the application's (the
-- repair time below is LOCALTIMESTAMP, and the application writes UTC wall-clock time):
--   PGTZ=UTC psql -X -v ON_ERROR_STOP=1 --single-transaction -f repair_order_history.sql
-- REPEATABLE READ: if the application changes an order this repairs while it runs, the run fails with a serialization
-- error and changes nothing; run it again. Each repaired order row stays locked until the commit, so a status change
-- that comes after the repair also lands after it in the history.
SET TRANSACTION ISOLATION LEVEL REPEATABLE READ;


-- Orders an old pod created have no history row: one row for the status they are in now, with occurred_at and
-- updated_at at the order's created_at, as V7_1's backfill.
WITH missing AS (
    SELECT o.id, o.status, o.created_at
    FROM orders o
    WHERE NOT EXISTS (SELECT 1 FROM order_status_history h WHERE h.order_id = o.id)
),
recorded AS (
    INSERT INTO order_status_history (order_id, from_status, to_status, actor_type, actor_id, reason, trace_id, occurred_at)
    SELECT id, NULL, status, 'SYSTEM', NULL, 'backfill', NULL, created_at
    FROM missing
    ORDER BY id
    RETURNING order_id
)
UPDATE orders
SET updated_at = created_at
WHERE id IN (SELECT order_id FROM recorded);


-- Orders whose status an old pod changed after their latest history row: one row for that change. When it happened
-- is unknown, so the row and updated_at carry the time of this repair.
WITH latest AS (
    SELECT DISTINCT ON (h.order_id) h.order_id, h.to_status
    FROM order_status_history h
    ORDER BY h.order_id, h.id DESC
),
drifted AS (
    SELECT o.id, l.to_status AS from_status, o.status AS to_status
    FROM orders o
    JOIN latest l ON l.order_id = o.id
    WHERE l.to_status <> o.status
),
recorded AS (
    INSERT INTO order_status_history (order_id, from_status, to_status, actor_type, actor_id, reason, trace_id, occurred_at)
    SELECT id, from_status, to_status, 'SYSTEM', NULL, 'backfill', NULL, LOCALTIMESTAMP
    FROM drifted
    ORDER BY id
    RETURNING order_id
)
UPDATE orders
SET updated_at = LOCALTIMESTAMP
WHERE id IN (SELECT order_id FROM recorded);
