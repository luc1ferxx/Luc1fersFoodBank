# Data Tier

One PostgreSQL primary carries the whole application, and the measured limit so far was the connection pool
(~1,000 orders/s on one machine), not the database. Three changes keep it that way for longer, and a plan exists for
when it is not enough:

- **Monthly partitions (V10).** `audit_log`, `order_status_history` and `processed_events` are range-partitioned by
  month on PostgreSQL. `PartitionMaintenanceService` keeps three months ahead, drops whole months past their
  retention instead of deleting rows (14 days for `processed_events`; the two audit trails are kept unless a retention
  is set), and runs on one instance at a time through an advisory lock. Because a unique constraint of a partitioned
  table must contain the partition column, `processed_events` deduplication is serialized per key with a
  transaction-scoped advisory lock and an insert that checks every partition, plus a unique index per partition. H2
  keeps plain tables. Gauges and alerts watch the months ahead and the default partitions.
- **Public order numbers (V10_1).** `orders.order_no` is a 64-bit Snowflake id (timestamp, worker id, sequence)
  assigned at checkout, returned by the order APIs (as a string) and used in notifications; the customer UI names
  orders by it, and so do the merchant and rider consoles, whose tables copy it from the order events (V14), while the
  internal `id` stays the key of every table and API path.
  Existing orders were numbered from their creation time with a reserved worker id.
- **Worker ids (V10_2).** Each instance needs its own worker id: configured (`APP_IDS_WORKER_ID`) for single
  instances and StatefulSets, or leased from `snowflake_worker_leases` on Kubernetes, where a timestamp fence keeps a
  stalled pod and its successor apart. Clock steps back are waited out (up to 50 ms) or refused with `503`.
- **Sharding, deferred.** [docs/adr/0001-order-sharding.md](adr/0001-order-sharding.md) plans the split of the
  order domain across databases by `customer_id` (co-located tables, application-level routing, per-shard outbox
  relays, a bucket-by-bucket migration with rollback) and says why it is not done yet.

Operations (retention settings, alerts, manual partition commands, worker-id setup) are in
[docs/operations.md](operations.md) section 9.
