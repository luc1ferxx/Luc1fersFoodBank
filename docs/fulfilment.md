# Order Fulfilment (event-driven)

After payment, the order lifecycle is driven by Kafka. Separate modules inside the same Spring Boot app (a modular
monolith under `com.laioffer.onlineorder.fulfilment`) consume the events, each with its own consumer group and its
own tables:

| Module | Package | Owns | Reacts to |
| --- | --- | --- | --- |
| Order | `fulfilment.order` (+ `service.OrderService`) | `orders`, the order state machine | `delivery.picked_up` (records `orders.picked_up_at`), `delivery.delivered` |
| Merchant | `fulfilment.merchant` | `merchant_accounts`, `merchant_orders` (inbox), `merchant_after_sales_cases` (after-sales inbox) | `order.paid` and later order events; after-sales events of customer requests |
| Timeout | `fulfilment.timeout` | `order_deadlines` | `order.created`, `order.paid`, later order events, `merchant.order_received` |
| Dispatch | `fulfilment.dispatch` | `riders` (rider logins, on duty), `deliveries`, `dispatch_orders` | `order.accepted`, `order.cancelled` |
| Notifications | `service.OrderNotificationService`, `fulfilment.notification` | `order_notifications` | order events, delivery events, after-sales events |
| After-sales | `aftersales` (order domain; see [After-Sales Refunds](after-sales.md#after-sales-refunds)) | `after_sales_cases` (+ lines, history), `refunds`, the after-sales and refund state machines | no events; driven by its API, its poller, and the order module's cancels and merchant answers |

Every order now belongs to one restaurant: a cart only holds items of one restaurant (adding another restaurant's item
returns `409 CART_RESTAURANT_CONFLICT`) and checkout records `orders.restaurant_id`. Orders placed before this change
have no restaurant and stay on the admin-driven flow (`PATCH /orders/{id}/status`); the fulfilment modules ignore
their events.

> **The merchant accept timeout only applies to staffed restaurants.** It is on by default
> (`FULFILMENT_ACCEPT_TIMEOUT_ENABLED=true`) and cancels a paid order that no merchant accepts within `accept-timeout`
> (`MERCHANT_TIMEOUT`), but only when the order's restaurant had at least one **active** merchant account when the
> merchant inbox received the order. The merchant module decides that from its own `merchant_accounts` and announces
> it as `merchant.order_received`; the timeout module sets the `ACCEPT` deadline only from that event and never reads
> merchant tables. The deadline runs from when the inbox received the order (the event time of
> `merchant.order_received`), not from the payment, so an inbox that lagged behind `order.paid` (a backlog, a replayed
> dead letter) still gives the merchant the whole window instead of a deadline that is already due. A restaurant
> without staff (none provisioned, or all deactivated before the order arrived) therefore
> never gets its paid orders auto-cancelled: they stay `PAID` in its inbox until a merchant or an admin moves them, and
> `onlineorder.fulfilment.merchant.orders_received{staffing="unstaffed"}` counts them. A deadline set while the
> restaurant was staffed still fires if its merchant is deactivated afterwards. The payment timeout for unpaid `PLACED`
> orders runs as before. A paid order (`PAID` or `ACCEPTED`) that is cancelled, by anyone, gets an automatic refund:
> the cancel opens an `ORDER_CANCELLED` after-sales case and its refund of the order total in the same transaction
> (ADR 0002 D12), and its cancellation notice, whoever cancelled, says the payment will be refunded automatically
> (after the pickup, the customer's own cancel names the share instead).
> Once the rider picked the order up (`delivery.picked_up`, recorded as `orders.picked_up_at` by the order-delivery
> consumer), a customer who cancels the still `ACCEPTED` order gets only
> `AFTER_SALES_PICKED_UP_CANCEL_REFUND_PERCENT` (50) percent of the total back, rounded half-even; the case still
> requests the whole order and approves that share. An admin's cancel after the pickup still refunds the whole order.
> The after-sales poller then executes the refund through the refund gateway (simulated here) and marks the case
> `REFUNDED`, which sends the customer a "Refund completed" notification; a refund the gateway refuses, or that keeps
> failing, ends `FAILED` and leaves its case `REFUNDING`. An order without a captured payment (marked `PAID` by an
> admin, or paid before payments were recorded) or with a total of 0.00 gets none and keeps the old notices; after a
> reject or an accept timeout, the former's still asks the customer to contact support.

## Event flow

```text
POST /payments/checkout            order PAID ---------------------- order.paid
                                                                        |-> merchant: inbox row (PAID); if the restaurant has an
                                                                        |      active merchant -> merchant.order_received
                                                                        |      `-> timeout: ACCEPT deadline = received_at + accept-timeout
                                                                        |-> timeout: PAYMENT deadline dropped
                                                                        `-> notifications: "Payment confirmed"
POST /merchant/orders/{id}/accept  order ACCEPTED ------------------ order.accepted
                                                                        |-> dispatch: least-loaded rider, delivery ASSIGNED
                                                                        |      `-> delivery.assigned -> notifications: "Rider assigned"
                                                                        |-> timeout: deadline dropped
                                                                        `-> merchant inbox, notifications
POST /merchant/orders/{id}/preparing  order PREPARING -------------- order.preparing
POST /rider/deliveries/{id}/picked-up delivery PICKED_UP ----------- delivery.picked_up
                                                                        |-> order: orders.picked_up_at (a customer cancel refunds a share)
                                                                        `-> notifications: "Order on the way"
POST /rider/deliveries/{id}/delivered delivery DELIVERED ----------- delivery.delivered
                                                                        |-> order: PREPARING -> COMPLETED ---- order.completed
                                                                        `-> notifications: "Order delivered"

no accept within accept-timeout   deadline poller: PAID -> CANCELLED (MERCHANT_TIMEOUT) -- order.cancelled
  (staffed restaurants only)
POST /merchant/orders/{id}/reject PAID -> CANCELLED (MERCHANT_REJECTED: <reason>) ------- order.cancelled
unpaid PLACED order               order.created -> PAYMENT deadline -> CANCELLED (PAYMENT_TIMEOUT)
customer cancels an ACCEPTED order order.cancelled -> dispatch cancels the delivery and releases the rider
```

Modules never read or write each other's tables or call each other's services; facts travel as events. The one
synchronous seam is the order module's command API, `OrderLifecycleCommands`: merchant commands and timeouts call it
so that the order row lock, the guarded transition and the outbox write happen in the caller's transaction. A merchant
accept and a timeout cancel therefore race on the same row lock and exactly one wins (the timeout only cancels an order
that is still `PAID`; a late accept gets `409 ORDER_STATUS_CONFLICT`). This is a deliberate exception to "events only":
the merchant's accept must answer with the order's real status, so it cannot be a fire-and-forget event. A merchant's
answer to a refund request goes through the same seam (`decideAfterSalesCase`), for the same reason. The merchant
controller resolves the login to a customer id like every other controller; the merchant module itself only reads its
own tables.

If the merchant never marked the order `PREPARING`, `delivery.delivered` moves it `ACCEPTED -> PREPARING -> COMPLETED`
in one transaction, emitting both events: delivered food was prepared, and both steps are guarded transitions. A
delivery event for a cancelled order is logged and ignored.

## Topics and consumer groups

| Topic | Published by | Consumer groups |
| --- | --- | --- |
| `order-events` (key: order id) | order module, via the outbox | `onlineorder` (notifications), `onlineorder-merchant`, `onlineorder-timeout`, `onlineorder-dispatch` |
| `delivery-events` (key: order id) | dispatch, via the outbox | `onlineorder-order-delivery`, `onlineorder-notification-delivery` |
| `merchant-events` (key: order id) | merchant, via the outbox | `onlineorder-timeout-merchant` |
| `after-sales-events` (key: order id) | after-sales, via the outbox | `onlineorder-merchant-aftersales`, `onlineorder-aftersales-notification` |
| `order-events.dlt` | the listener error handler | `onlineorder-dlt` |

All events go through the transactional outbox (`OrderEventOutboxService.enqueueEvent` stores any aggregate type and
topic). Every new consumer uses the shared `orderEventKafkaListenerContainerFactory` (retries, then the DLT; a replay
goes back to the source topic and reaches every group there, which is safe because every consumer is idempotent:
a `processed_events` mark in the same transaction as its writes, plus unique keys such as `merchant_orders.order_id`,
`order_deadlines (order_id, deadline_type)` and `deliveries.order_id`). New consumer groups start from the earliest
offset, like the existing one.

## Out-of-order events (dead letters replayed late)

`processed_events` drops exact duplicates. The outbox publishes an aggregate's events in sequence order, but a dead
letter replayed after later events of the same order were applied still reaches a group out of order. The consumers
that keep per-aggregate state (merchant inbox, dispatch, timeout) therefore record the last sequence they applied per
order (`consumer_aggregate_sequences`, through `EventSequenceTracker`) and skip an event whose sequence is not above it
(`onlineorder_events_stale_skipped_total`). The after-sales consumers do the same per case: the merchant projection,
and the customer notifications, which write no notice for a step the case has already left. The order and delivery
notifications skip a stale event per order and per delivery the same way. Only event types a consumer applies move
its position: dispatch ignores `order.preparing`, so an `order.accepted` dead-lettered for want of a rider and
replayed after it still gets one.
Events without a sequence (written before V9) and positions older than the processed-events retention fall back on the
rules below, which still hold for every module when the order of two events flips:

- **Order number:** the public `order_no` of order events v2 never changes, so it is the one fact every event may
  bring, stale or not: an event that carries it fills a merchant inbox row or a delivery that has none (created from a
  v1 event), and none replaces a stored number.
- **Merchant inbox:** an order event that finds no inbox row creates it with the status it reports (`order.cancelled`
  leaves a cancelled tombstone) and no `paid_at`. The merchant only sees rows with a `paid_at`; the late `order.paid`
  then only sets it (also when it is stale by sequence: the payment time is a fact no later event carries). A replayed
  `order.paid` therefore shows the order as `CANCELLED` (or `ACCEPTED`, ...) instead of a stale `PAID` row that no
  merchant command could resolve. An order cancelled before it was ever paid keeps such a hidden row.
- **Dispatch:** `order.accepted` and `order.cancelled` both create and lock the order's `dispatch_orders` row before
  they touch deliveries, so they serialize per order even on different consumers. `order.cancelled` sets
  `cancelled_at` there even when no delivery exists yet, and an `order.accepted` handled afterwards (for example one
  dead-lettered because no rider was active, replayed after the customer cancelled) assigns no rider.
- **Timeout:** a late `order.created` with a sequence is skipped once a later order event was applied. A deadline
  written by a late event without one, or by a late `merchant.order_received`, fires as a no-op: the poller only
  cancels an order that is still in the status the deadline waits in. `merchant.order_received` and the later
  order events travel on different topics, so this also covers an order accepted before its announcement arrived.
- **Order completion:** a `delivery.delivered` for a cancelled order is ignored.

## Fulfilment APIs

| Method | Path | Role | Purpose |
| --- | --- | --- | --- |
| `GET` | `/merchant/orders?status=PAID&limit=50` | `MERCHANT` | The merchant's inbox (own restaurant only) |
| `POST` | `/merchant/orders/{orderId}/accept` | `MERCHANT` | `PAID -> ACCEPTED` |
| `POST` | `/merchant/orders/{orderId}/reject` | `MERCHANT` | `PAID -> CANCELLED`, body `{"reason": "..."}` (required) |
| `POST` | `/merchant/orders/{orderId}/preparing` | `MERCHANT` | `ACCEPTED -> PREPARING` |
| `GET` | `/rider/me` | `RIDER` | The rider's profile: `rider_id`, `name`, `phone`, `active`, `on_duty`, `active_deliveries` |
| `PUT` | `/rider/me/availability` | `RIDER` | Go on or off duty, body `{"on_duty": true}`; off duty only stops new assignments |
| `GET` | `/rider/deliveries?status=ASSIGNED,PICKED_UP` | `RIDER` | The rider's own deliveries (default: the open ones, `ASSIGNED` and `PICKED_UP`) with `restaurant_name` |
| `POST` | `/rider/deliveries/{deliveryId}/picked-up` | `RIDER` | `ASSIGNED -> PICKED_UP` (own deliveries only) |
| `POST` | `/rider/deliveries/{deliveryId}/delivered` | `RIDER` | `PICKED_UP -> DELIVERED` (own deliveries only) |
| `GET` | `/admin/deliveries?order_id=&rider_id=` | `ADMIN` | Deliveries: one order's, one rider's open ones (`ASSIGNED`, `PICKED_UP`), or the 50 most recent |
| `POST` | `/admin/deliveries/{deliveryId}/picked-up` | `ADMIN` | Ops and tests: any delivery `ASSIGNED -> PICKED_UP` (audited as `delivery.picked_up`) |
| `POST` | `/admin/deliveries/{deliveryId}/delivered` | `ADMIN` | Ops and tests: any delivery `PICKED_UP -> DELIVERED` (completes the order; audited as `delivery.delivered`) |
| `POST` | `/admin/deliveries/{deliveryId}/reassign` | `ADMIN` | Move an open delivery to another rider, body `{"rider_id": 7}` (see below; audited as `delivery.reassign`) |

Merchant orders, rider deliveries and the admin delivery list carry `order_no`, the public order number as a JSON
string, right after `order_id` (the key the paths take). The merchant inbox and dispatch copy it from the order events
(v2, V14); it is absent for a row that only v1 events reached, rows from before V14 included, and the consoles then show
the order id.

Merchant commands are scoped to the merchant's restaurant (another restaurant's order is a `404`) and are idempotent:
repeating a command whose transition already happened returns the order unchanged. A reject of an order that was
cancelled for another reason (the customer, the accept timeout) is `409 ORDER_STATUS_CONFLICT`, not a successful
reject. Customers and admins get `403` on `/merchant/**`. Order responses (`GET /orders`) now also carry
`restaurant_id`, `cancel_reason` and `updated_at`.

Rider actions go through the same delivery state machine and outbox events as the admin endpoints and are idempotent
the same way; a delivery that cannot make the move is `409 DELIVERY_STATUS_CONFLICT`. A rider only reaches their own
deliveries: another rider's delivery is `404` (and absent from their list). Everyone but a rider gets `403` on
`/rider/**`.

Reassigning moves an open delivery (`ASSIGNED` or `PICKED_UP`; it keeps its status) to a rider who is active and on
duty, under the delivery row lock with both riders' rows locked in id order. The load moves with it and
`delivery.reassigned` is published (the order and notification consumers ignore it; the next delivery notification
names the new rider). Repeating it with the same `rider_id` returns the delivery unchanged; a finished delivery is
`409 DELIVERY_STATUS_CONFLICT`, an inactive or off-duty rider `409 RIDER_NOT_DISPATCHABLE`, an unknown delivery or rider
`404`. The previous rider no longer sees the delivery (`404` on it).

## Provisioning merchants and riders

Admins create merchant and rider logins through the API (`ROLE_ADMIN`; the POSTs and PATCHes are rate-limited per admin
like other admin writes). The login gets a BCrypt password through the shared `PasswordEncoder` and the same email and
password validation as `POST /signup`; an email any login already uses (in any letter case) is
`409 EMAIL_ALREADY_REGISTERED`.

| Method | Path | Body | Result |
| --- | --- | --- | --- |
| `POST` | `/admin/merchants` | `email`, `password`, `first_name`, `last_name`, `restaurant_id` | `201` merchant: a `ROLE_MERCHANT` login bound to the restaurant (`404` for an unknown restaurant) |
| `GET` | `/admin/merchants` | | `merchant_id`, `customer_id`, `email`, `first_name`, `last_name`, `restaurant_id`, `restaurant_name`, `active`, `created_at` |
| `PATCH` | `/admin/merchants/{merchantId}` | `{"active": false}` | the merchant |
| `POST` | `/admin/riders` | `email`, `password`, `first_name`, `last_name`, `phone` | `201` rider: a `ROLE_RIDER` login, active and off duty |
| `GET` | `/admin/riders` | | `rider_id`, `customer_id`, `email`, `first_name`, `last_name`, `name`, `phone`, `active`, `on_duty`, `active_deliveries`, `created_at` |
| `PATCH` | `/admin/riders/{riderId}` | `{"active": false}` and/or `{"on_duty": true}` | the rider: a left-out flag keeps its value, deactivating also takes them off duty, and an inactive rider cannot be put on duty (`409 RIDER_INACTIVE`) |

Every change here is in the audit log with the admin who made it (`merchant.create`, `merchant.update`,
`rider.create`, `rider.update`; a rider's own `PUT /rider/me/availability` is `rider.availability` with the rider's
login, see [Order State](correctness.md#order-state)).

Accounts are deactivated, never deleted. Deactivation takes effect on the account's next request without touching its
session: the merchant and dispatch modules look the account up on every merchant or rider request and answer `403`
for an inactive one, while `GET /me` still lists the role and shows `"active": false`. A deactivated merchant no
longer makes its restaurant staffed (see the accept timeout above); a deactivated rider is never assigned, and a
rider's own availability change cannot put them back on duty even when it races the deactivation.

Deliveries a deactivated rider already holds stay assigned to them, and that rider can no longer move them (`403`).
The `active_deliveries` in the PATCH response shows how many there are. List them with
`GET /admin/deliveries?rider_id={riderId}`, then reassign each one to an on-duty rider
(`POST /admin/deliveries/{id}/reassign`; a script over more than 60 should pace at one per second or retry a
`429 RATE_LIMITED`, which moved nothing) or reactivate the rider. An order that is still `ACCEPTED` can also be
cancelled (`PATCH /orders/{id}/status`, which cancels the delivery and releases the rider); a `PREPARING` one cannot.
Do not clear them with `POST /admin/deliveries/{id}/delivered`: that completes the customer's order and sends "Order
delivered" for food nobody delivered.

The orchestration lives in `com.laioffer.onlineorder.staff` (admin web layer): in one transaction it creates the login
through `CustomerService` and binds it through the merchant module's `MerchantAccountService` or the dispatch
module's `RiderService`; the modules still only touch their own tables.

Demo staff (seeded only with `classpath:db/demo`): merchants `merchant.burgerking@laifood.com`,
`merchant.sgdtofu@laifood.com`, `merchant.fashionwok@laifood.com` (password `merchant123`), and riders
`rider.alice@laifood.com`, `rider.bob@laifood.com`, `rider.carol@laifood.com` (password `rider123`, bound to Demo
Rider Alice, Bob and Carol, which start on duty). The seed only grants the role and binding to logins it created itself
(their seeded `{noop}` password): an account someone registered with one of these emails through `POST /signup` stays
a plain customer.

**No rider on duty.** Dispatch only assigns riders that are active and on duty (least-loaded first, `SKIP LOCKED` as
before). With none, `order.accepted` is retried and then dead-lettered (`OnlineOrderDeadLettersGrowing` fires, and
`onlineorder.fulfilment.dispatch.assignments{result="no_rider_attempt"}` grows). The operator first gets a rider on
duty: a rider does it with `PUT /rider/me/availability`, or an admin who knows the rider is working sets
`PATCH /admin/riders/{id}` `{"on_duty": true}` (add `"active": true` for a deactivated rider; a newly provisioned rider
starts off duty, and reactivating alone leaves a rider off duty). Check `GET /admin/riders` for `"active": true,
"on_duty": true`, then replay the dead letter with `POST /dead-letters/{id}/replay`. A replay before that fails again
and goes back to the dead-letter topic; a replay after the customer cancelled assigns no rider.

## Configuration

| Key | Default | Purpose |
| --- | --- | --- |
| `app.fulfilment.accept-timeout-enabled` (`FULFILMENT_ACCEPT_TIMEOUT_ENABLED`) | `true` | Run the accept timeout for staffed restaurants (see the note at the top of this section); switching it off also drops pending accept deadlines without cancelling |
| `app.fulfilment.accept-timeout` (`FULFILMENT_ACCEPT_TIMEOUT`) | `10m` | Cancel a `PAID` order not accepted in time (`MERCHANT_TIMEOUT`) |
| `app.fulfilment.payment-timeout` (`FULFILMENT_PAYMENT_TIMEOUT`) | `15m` | Cancel a `PLACED` order not paid in time (`PAYMENT_TIMEOUT`) |
| `app.fulfilment.deadline-poller.enabled` | follows `app.kafka.enabled` | Run the deadline poller |
| `app.fulfilment.deadline-poller.interval` | `1s` | Poll interval (timeout precision) |
| `app.fulfilment.deadline-poller.batch-size` | `100` | Deadlines fired per run, one transaction each |
| `app.fulfilment.deadline-poller.retry-backoff` | `30s` | Delay before retrying a deadline whose firing failed |
| `app.fulfilment.delivery-topic` (`KAFKA_DELIVERY_TOPIC`) | `delivery-events` | Delivery event topic |
| `app.fulfilment.merchant-topic` (`KAFKA_MERCHANT_TOPIC`) | `merchant-events` | Merchant event topic (`merchant.order_received`) |
| `app.fulfilment.*-group` | see the table above | Consumer group per module |
| `app.fulfilment.listener-concurrency` (`FULFILMENT_LISTENER_CONCURRENCY`) | `app.kafka.listener-concurrency` (3) | Consumer threads of each of the six fulfilment listeners and the two after-sales listeners |

Metrics: `onlineorder.fulfilment.merchant.decisions{decision=accepted|rejected|timeout}`,
`onlineorder.fulfilment.merchant.orders_received{staffing=staffed|unstaffed}`,
`onlineorder.fulfilment.timeouts{type=accept|payment,result=cancelled|skipped}`,
`onlineorder.fulfilment.dispatch.assignments{result=assigned|duplicate|cancelled|no_rider_attempt}`. `no_rider_attempt`
counts attempts, not orders: with no active rider each listener retry and each DLT replay counts once more.

Capacity: with Kafka on, the app runs 10 listener containers (the 6 fulfilment listeners, the 2 after-sales listeners
on `after-sales-events`, notifications, the DLT). At the default concurrency of 3 that is 30 consumer threads, against 6
before these modules. A thread holds a pooled connection only while it handles a record, but they share the
30-connection pool with HTTP requests, the outbox publisher and the deadline and after-sales pollers, and every order
event now costs one short transaction in each of the 4 groups on `order-events` instead of 1. The k6 order flow has not been rerun with these consumers yet; if it shows connection
waits, lower `FULFILMENT_LISTENER_CONCURRENCY` before raising the pool.

## Timeouts: why a deadline table instead of a Kafka delay

Kafka has no delayed delivery: a consumer can only sleep or pause a partition, which blocks every later record behind
it and is bounded by `max.poll.interval.ms`. The usual alternatives each move the deadline out of the transaction that
decides it:

- **Delay topics** (for example one topic per delay tier, each consumer pausing until its head record is due) work for a
  few fixed delays, but need a topic and consumer per tier and still re-check the order on expiry.
- **Redis ZSET** (score = due time, claimed with `ZRANGEBYSCORE` plus an atomic Lua pop) is fast, but the `ZADD` is not
  in the database transaction, so a crash between commit and `ZADD` silently loses a deadline unless reconciled.
- **RocketMQ delay levels / timer messages** are native, but mean running a second broker for one feature.

Here the timeout consumer writes the deadline row in the same transaction as its `processed_events` mark, and the
poller claims due rows with `FOR UPDATE SKIP LOCKED` (several instances never fire the same deadline), cancels through
the guarded transition only if the order still waits, and deletes the row, all in one transaction with the
`order.cancelled` outbox event. The table only holds pending deadlines and is polled through its `due_at` index. The
poller runs on its own thread instead of the shared scheduler (4 threads), so a burst of due deadlines cannot delay
the outbox publisher.

Follow-up: real rider location and ETA. The k6 run with the fulfilment consumers on is in
[docs/operations.md](operations.md) section 12, "Connections: listener threads and the pool".
