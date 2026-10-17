# ADR 0001: Splitting order data across databases (sharding)

- Status: **Proposed, deferred.** The design below is the plan for when one PostgreSQL primary is no longer enough.
  Nothing in it is implemented except the prerequisites marked "done".
- Date: 2026-10-16
- Scope: the order domain (carts, orders, their history and idempotency records, the outbox) of the backend in
  `backend/`.

## Context

Everything lives in one PostgreSQL database today: the order domain, the catalog, customers and staff, the
fulfilment modules (merchant, timeout, dispatch), the outbox, the consumers' deduplication marks, the audit trails.
Checkout is one local ACID transaction (cart row lock, order, item snapshots, history row, outbox event, idempotency
record; README "Correctness Model"), and the fulfilment modules already talk to the order module only through Kafka
events plus one synchronous seam, `OrderLifecycleCommands`.

### What the repository measured

| Measurement | Number | Source |
| --- | --- | --- |
| Checkout throughput, one 15-core machine, Hikari pool of 30 | ~1,000 orders/s | comment on `spring.datasource.hikari.maximum-pool-size`, commit 17057b6 |
| Same with a pool of 10 | ~750 orders/s, 182 requests waiting for a connection | same |
| k6 `load-tests/order-flow.js`, 50 VUs (closed loop with think time) | ~19 checkouts/s, ~130 req/s, backend nearly idle; 300 VUs are needed before Hikari and Tomcat show strain | k6 runs of 2026-10-14 |
| Outbox relay before tuning (20 events / 3 s) | ~6.7 events/s; 164k of 166k events still pending after the run (~7 h to drain) | `app.outbox.batch-size` comment |
| Outbox relay now | up to 200 events per 500 ms claim per instance | `application.yml` |
| Outbox claim query with 1M published rows ahead of a 100k backlog | 115-225 ms per claim without the partial index, 0.1 ms with it (V3) | `V3__Add_outbox_unpublished_index` |
| Cached catalog reads (`load-tests/catalog-read.js`, 50 VUs) | 52-56k req/s, p99 2.3-2.8 ms, no database connection | README "Catalog Caching" |

Connection budget (`deploy/k8s`): the base manifests allow 3 replicas x 30 pooled connections = 90 and staging
2 x 30 = 60, under PostgreSQL's default `max_connections` of 100; production allows 6 replicas x 20 = 120, already
above that default, so the production database runs with a raised `max_connections` (at least 120 plus room for
migrations, operators and replication). Each further replica adds 20 connections. This is the first limit the
deployment approaches, and it is solved by PgBouncer (transaction mode) long before by sharding (see the triggers
below).

The bottleneck every measurement hit first was the connection pool and the relay's batch size, not the database's
CPU, I/O or WAL. At ~1,000 orders/s a checkout writes roughly 8-10 rows (order, 1-3 item snapshots, history row,
outbox event, idempotency record, cart updates), so ~10k row writes/s on one primary: well inside what a single
managed PostgreSQL instance with fast storage sustains, before any vertical scaling.

### What already scales the single database

- **Monthly partitions** (V10, done): `audit_log`, `order_status_history` and `processed_events` are range-partitioned
  by month; retention drops whole months instead of deleting rows; the hot month's indexes stay small. Next
  candidate: `outbox_events` (partition by `created_at`, drop months whose events are all published).
- **Snowflake ids** (done): `DistributedIdGenerator` issues 64-bit, time-ordered ids from any instance without a
  database round trip (worker ids configured or leased from `snowflake_worker_leases`). Today they number orders
  publicly (`orders.order_no`); they are the id source a sharded order domain needs (below).
- **64-bit keys** (V7, done), the two-level catalog cache, and read replicas for read-only traffic where configured.

## Decision

1. **Do not shard yet.** Keep one primary, scale it vertically, partition the growing tables and move reads to
   replicas. Revisit when one of the triggers below fires.
2. **When sharding becomes necessary, shard the order domain by `customer_id`**, route in the application, keep
   every write transaction on one shard, and keep the rest of the system (catalog, identity, fulfilment modules,
   operations) out of the shards. The rest of this document is that plan, so that the prerequisites can be built
   ahead of time and nothing in between makes it harder.

### Why not now

- **Headroom.** One laptop-class machine sustained ~1,000 orders/s, limited by the pool; production traffic of this
  application is orders of magnitude below that. A managed PostgreSQL instance can grow 4-8x vertically before
  sharding is the cheaper option.
- **Cost.** N databases mean N migrations per release, N backups and restore drills, N sets of alerts, a routing
  layer in every data access, scatter-gather for every admin view, and no foreign keys or joins across the split.
- **The prerequisites are not met** (next section): sequence-based keys, a synchronous cross-module seam that writes
  two modules' tables in one transaction, and foreign keys that cross the future boundary. Each is worth fixing on
  its own schedule, and sharding before them would mean distributed transactions, which this design rules out.

### Triggers to revisit (any one, sustained over a week of peak traffic, after vertical scaling)

- primary CPU above 60% or storage IOPS above 70% of the provisioned limit at peak;
- WAL generation that replicas cannot apply within the lag budget of the read routing (replica lag alert firing at
  peak);
- the order tables (orders, items, history) above ~1 TB or ~5 billion rows even with partitioning, or a restore of the
  primary that no longer fits the recovery time objective;
- connection demand above what PgBouncer in transaction mode can multiplex onto the primary;
- `OnlineOrderLatencySLO*` burning because of database lock or I/O waits (not pool saturation, which is solved by
  more connections or replicas).

## Design for when it is needed

### Shard key: `customer_id`

Every hot write path of the order domain is scoped by the customer: add to cart, checkout, cancel, order list, order
detail, order history, notifications, idempotency records (`UNIQUE (customer_id, scope, idempotency_key)`). With
`customer_id` as the key, checkout stays one local transaction on one shard, exactly as today, and every customer
request routes with the customer id from the session.

Rejected keys:

- `order_id`: the cart (one per customer) and the order list would scatter, and checkout would write the cart on one
  shard and the order on another.
- `restaurant_id`: hot restaurants make hot shards; the customer's order list would scatter across all shards; the
  merchant side already reads its own event-fed projection, so it gains nothing.
- region/city: a valid later addition (region first, then customer within a region) for data residency, but not a
  load-balancing key.

**Buckets.** `bucket = hash(customer_id) mod 1024`; a small `shard_map(bucket, shard)` table (in the global
database, cached in every pod and refreshed through Redis pub/sub like the catalog cache invalidation) maps buckets
to physical shards. The bucket count never changes; rebalancing moves buckets.

### What lives where

| Data | Placement | Notes |
| --- | --- | --- |
| `carts`, `order_items` (cart lines) | order shard | keyed by customer |
| `orders`, `order_history_items`, `order_status_history` | order shard | an order never spans shards |
| `idempotency_requests` | order shard | written in the checkout transaction |
| `order_notifications` | order shard | written by the notification consumer of that shard's events |
| `outbox_events` | order shard | must commit with the order change; one relay per shard |
| `processed_events` | with each consumer | the mark must commit with the consumer's own write |
| `audit_log` | order shard for order actions, global for staff/catalog actions | written in the action's transaction |
| `customers`, `authorities` (identity) | global | login by email needs one lookup; `customer_id` determines the shard without a lookup |
| `restaurants`, `menu_items` (catalog) | global | read-mostly, cached; checkout copies the item into `order_history_items` already |
| `merchant_accounts`, `merchant_orders` | merchant module database | already fed by events only |
| `order_deadlines` | timeout module database | needs `customer_id` added (see seams) |
| `riders`, `deliveries`, `dispatch_orders` | dispatch module database | `deliveries` already carries `customer_id` |
| `dead_letter_events` | global operations database | the DLT consumer is not order-scoped |
| `snowflake_worker_leases`, `shard_map` | global | coordination |

Foreign keys that cross the boundary go: `orders.customer_id -> customers`, `order_items.menu_item_id ->
menu_items`, `order_notifications.customer_id -> customers`, `idempotency_requests.customer_id -> customers`. The
application already validates these on the way in (the cart only takes existing menu items; the customer comes from
the session); deleting customers becomes an event (`customer.deleted`) that each shard applies.

### Synchronous seams to remove first

- **`OrderLifecycleCommands`.** Merchant accept/reject/preparing (`MerchantOrderService`), the deadline poller
  (`OrderDeadlineProcessor.cancelIfStillIn`) and delivery completion (`DeliveryCompletionService`) call the order module
  synchronously, inside their own transaction, so today the merchant inbox row and the order row change atomically.
  Across databases that needs either a distributed transaction (rejected) or a saga: the module records its intent
  locally and emits an event through its outbox (`merchant.order_accept_requested`), the order shard's consumer
  applies the guarded transition (row lock, status check, history row, `order.accepted` on its outbox), and the
  merchant projection settles on `order.accepted` / `order.cancelled`. The merchant API answers `202 Accepted` with
  the pending state; the race between an accept and a timeout cancel is still decided by the order row lock on the
  shard, and the loser learns it from the event. Consumers stay idempotent (`processed_events`), and replays stay
  correct because the order shard checks the transition, not the event order.
- **Routing information in the modules.** Every command must reach the shard of its order: `merchant_orders` and
  `deliveries` already store `customer_id`; `order_deadlines` needs it (from the event payload, which carries it).
- **Same-transaction cross-module writes** (audit rows written by staff actions that also touch order data) follow
  the same pattern: the order-side change and its audit row commit on the shard; staff-side rows commit globally.

### ID strategy

Sequences are per database, so `BIGSERIAL` keys of sharded tables would collide when rows move between shards. Before
the split, the order domain's primary keys switch to Snowflake ids from `DistributedIdGenerator` (assigned by a Spring
Data `BeforeConvertCallback`, the same way `OrderNumberCallback` assigns `orders.order_no` now). Keys are already
`BIGINT` (V7). Sequence values stay far below 2^40 and Snowflake ids issued from 2026 on are above 2^59, so old and
new keys never collide and rows can move between shards with their ids. `orders.order_no` stays the public
number; ids carry no routing information (the customer id does), so they stay opaque and no re-numbering is needed
when buckets move. Worker ids stay global (`snowflake_worker_leases` in the global database).

### Query routing: application-level router, not ShardingSphere-JDBC

| | Application router (`AbstractRoutingDataSource` + shard context) | ShardingSphere-JDBC |
| --- | --- | --- |
| How a query finds its shard | the service sets the shard from `customer_id` before the transaction starts; the routing data source picks the shard's pool | SQL is parsed and rewritten using sharding rules on table columns |
| Fit with this code | explicit SQL (`ON CONFLICT`, `FOR UPDATE SKIP LOCKED`, CTEs, advisory locks) runs unchanged; Spring Data JDBC sees one `DataSource` | every statement must be understood by its parser; unsupported syntax fails at runtime |
| Cross-shard queries | explicit scatter-gather helpers, only where written | silently fan out, which hides expensive queries |
| Transactions | one shard per transaction, enforced (fail fast without a shard context) | offers XA / BASE distributed transactions this design does not want |
| Operating cost | a few hundred lines of routing code and tests | another runtime layer, its configuration, upgrades and debugging |

Decision: the application router. Each shard is a primary plus replicas, so routing is two-level: shard first, then
primary or replica, which composes with read/write routing. Every repository call on order data runs inside a shard
context; a missing context is a bug and throws. ShardingSphere is worth reconsidering only if the cross-shard query
surface grows beyond the admin views below.

### Merchant, rider and admin reads

- **Merchant inbox and dispatch** read their own event-fed projections (`merchant_orders`, `deliveries`), which never
  touch the order tables. With N shards they are fed by N outbox relays through the same Kafka topics (keyed by order
  id), so nothing changes for them.
- **Admin by order id** (`PATCH /orders/{id}/status`, the order and history views): scatter-gather the N shards in
  parallel (at most one hit; admin traffic is tiny), later an event-fed `order_directory(order_id, order_no,
  customer_id)` in the global database if N grows.
- **Audit log, failed outbox events**: per-shard lists merged k-way by `(occurred_at, id)` with keyset pagination per
  shard (the API already pages by keyset).
- **Reporting and analytics**: change data capture from every shard into a warehouse, never queries against the
  shards.

### Outbox relay per shard

Each shard keeps its own `outbox_events` (it must commit with the order change) and runs its own relay: the existing
`OutboxEventPublisher` with one claim loop per shard data source (claims use `FOR UPDATE SKIP LOCKED`, so every pod
can relay every shard), or a Debezium outbox router per shard. Per-order ordering holds because an order's events
live on one shard and keep the order id as the Kafka key. Relay capacity grows with the number of shards.

### Migration plan (1 database to N)

Phase 0, prerequisites (each ships on its own, with N = 1):

1. Snowflake primary keys for the order-domain tables (above).
2. Drop the cross-boundary foreign keys; add the application checks and the `customer.deleted` event.
3. Turn `OrderLifecycleCommands` into events (the saga above); add `customer_id` to `order_deadlines`.
4. Introduce the routing data source with one shard and the global/shard split of the schema (two Flyway locations:
   `db/global`, `db/shard`); every order-domain call runs in a shard context; tests fail without one.
5. Partition `outbox_events` (smaller relay tables per shard later).

Phase 1, provision: create the shard databases (start with N = 2 behind the 1,024 buckets), run `db/shard` migrations
on each, keep all buckets mapped to the old database (now "shard 0").

Phase 2, move buckets, a few at a time:

1. **Backfill and continuous copy.** Logical replication from shard 0 to the target shard with publication row
   filters (PostgreSQL 15+: `CREATE PUBLICATION ... FOR TABLE orders WHERE (bucket = ...)`; a stored `bucket` column
   is added in phase 0 for this and made part of each table's replica identity, which row filters on updates and
   deletes require), so the initial copy and every later write of those customers land on both databases. This is the "dual write", done by the database: an application-level dual write was rejected, because
   two writes without a distributed transaction drift apart on every partial failure.
2. **Verify.** Per bucket and table: row counts, a checksum over the ordered rows (`md5(string_agg(...))` per id
   range), and the latest `order_status_history` row matching `orders.status`; replication lag at zero.
3. **Cut over.** Mark the buckets `moving` in `shard_map`: their requests answer `503` with `Retry-After` for a few
   seconds (checkout and status changes are idempotent, so clients retry safely with their `Idempotency-Key`). Wait
   until shard 0's relay has published every outbox row of those buckets and replication has caught up, flip the
   buckets to the target shard, start reverse replication (target to shard 0) for the rollback window, unmark.
4. **Roll back** (within the window): mark `moving`, wait for reverse replication, flip back. Nothing is lost because
   both directions were replicated; duplicate events from the overlap are absorbed by the consumers' deduplication.
5. **Clean up** after the window: stop replication, delete the moved buckets' rows from shard 0 (by month partitions
   where possible).

### Rebalancing

Adding a shard moves a set of buckets with the same procedure; draining one moves all of its buckets away. 1,024
buckets allow up to 1,024 shards and fine-grained moves (about 0.1% of the customers each). A single customer cannot
be split, which is fine for consumer ordering; a very large business account would get a bucket of its own.

## Consequences

- Positive: the prerequisites (Snowflake keys, event-only module seams, no cross-domain foreign keys) improve the
  design on their own and keep the eventual split mechanical; the correctness model (one local transaction per
  checkout, transactional outbox, idempotent consumers) survives sharding unchanged.
- Negative: once sharded, admin views are scatter-gather, operational work multiplies by N, and the merchant commands
  become asynchronous (UI shows a pending state until the order event arrives).
- Until a trigger fires, the team pays none of that cost; the partitions, replicas and Snowflake ids cover the growth
  in between.
