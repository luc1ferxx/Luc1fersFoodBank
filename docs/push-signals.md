# Push Signals (SSE)

The merchant's orders and after-sales inbox, the rider's deliveries and the customer's notifications used to be
polled every 10-15 seconds. Now the server pushes **change signals** over Server-Sent Events
([ADR 0003](adr/0003-push-signals.md), `com.laioffer.onlineorder.push`): a signal only says "this list of yours
changed", and the client answers it by reading the REST endpoint it already uses, at most once a second per topic
(`PUSH_SIGNAL_COALESCE_MS`: the first signal of a quiet second reads at once, the rest of that second fold into one
read when it ends). Signals carry no data, so a lost or duplicated one costs a late or an extra read, never a wrong
screen, and the REST reads (with their access checks, read-replica rules and stale-response handling) stay the only
source of data. The consoles keep polling, every 60 seconds while their stream is open and at the old interval while
it is not.

`GET /events/stream` (session login, `text/event-stream`, servlet async: an open stream holds a connection but no
thread) sends, exactly:

```text
retry: 5000

event: signal
data: {"topic":"merchant.orders"}

:keepalive

event: forbidden
data: {}

```

`retry: 5000` comes first (the browser's reconnect delay), a `:keepalive` comment every 25 seconds, a signal per
committed change, and `event: forbidden` (then the end of the response) when a merchant or rider login was deactivated
while its stream was open. The response carries `Cache-Control: no-cache` and `X-Accel-Buffering: no`.

| Topic | Recipient | Published after a commit of |
| --- | --- | --- |
| `notifications` | `customer:{customerId}` | every notification `OrderNotificationService` inserted (a redelivered event inserts and signals nothing) |
| `merchant.orders` | `restaurant:{restaurantId}` | the merchant inbox consumer when it changed the row (paid, accepted, preparing, completed, cancelled, also from an admin's change or a timeout), and the merchant's accept, reject and preparing |
| `merchant.after_sales` | `restaurant:{restaurantId}` | the merchant's after-sales projection, from its consumer or the merchant's answer |
| `rider.deliveries` | `rider:{riderId}` | dispatch: assignment, reassignment (both riders), pickup, delivery, cancellation |

Every stream subscribes to `customer:{its customer id}`; a merchant login also to its restaurant, a rider login also to
its rider, looked up once at connect like the merchant and rider endpoints do. The customer's order list and refund
cases have no topic of their own: every change they show also writes a notification, which makes the page read them
again.

| Answer | When |
| --- | --- |
| `401 UNAUTHORIZED` | no session |
| `403 FORBIDDEN` | a merchant login without an active merchant account, a rider login without an active rider |
| `429 PUSH_STREAM_LIMIT` | the login already holds `app.push.max-streams-per-user` streams on this instance |
| `503 SERVICE_UNAVAILABLE` | this instance holds `app.push.max-streams` streams, or is shutting down |

How a signal travels: the writer calls `PushSignals.publish(recipient, topic)` in its transaction; after the commit
(nothing for a rollback) the signal is published on the Redis pub/sub channel `onlineorder:push:v1` as
`{"recipient":"restaurant:5","topic":"merchant.orders"}`, every instance's subscription hands it to its own streams of
that recipient, and the browser re-reads. The publish and the stream writes run on threads of their own
(`push-publisher`, `push-delivery`), so a writer never waits for Redis or for a client. Delivery is at most once:
Redis down (publishing fails open: logged, counted, the transaction stands), a missed subscription window or a
dropped connection lose signals, and the client re-reads every list on each (re)connect while the fallback poll
covers the rest. `app.push.fanout=local` skips Redis and delivers in-process only (the `h2` profile and the tests).

On each keepalive the server re-checks the active flag of every merchant and rider stream (on the primary) and closes
a deactivated one with `event: forbidden`. A stream ends after `app.push.stream-timeout` (30 minutes) and the browser
reconnects; on shutdown an instance completes its streams before Tomcat's graceful shutdown, so the browsers move to
another pod instead of holding this one. The keepalive runs on its own `push-keepalive` thread, not on a
`TaskScheduler` bean. On Kubernetes, `/events/stream` has its own Ingress with a 120-second read timeout and response
buffering off ([docs/operations.md](operations.md) section 13).

| Key | Default | Purpose |
| --- | --- | --- |
| `app.push.fanout` (`PUSH_FANOUT`) | `redis` (`local` in the `h2` profile) | `redis`: through the Redis channel to every instance; `local`: this instance's streams only |
| `app.push.channel` (`PUSH_CHANNEL`) | `onlineorder:push:v1` | Redis pub/sub channel; the suffix versions the message format |
| `app.push.stream-timeout` (`PUSH_STREAM_TIMEOUT`) | `30m` | A stream's lifetime (its own async timeout; `spring.mvc.async.request-timeout` does not apply) |
| `app.push.keepalive-interval` (`PUSH_KEEPALIVE_INTERVAL`) | `25s` | Keepalive comment and the re-check of merchant and rider streams |
| `app.push.max-streams-per-user` (`PUSH_MAX_STREAMS_PER_USER`) | `5` | Open streams of one login per instance, then `429 PUSH_STREAM_LIMIT` |
| `app.push.max-streams` (`PUSH_MAX_STREAMS`) | `2000` | Open streams per instance, then `503` |

Metrics: `onlineorder.push.streams` (gauge, open streams on this instance), `onlineorder.push.signals{topic}`
(published after a commit), `onlineorder.push.deliveries{topic}` (written to a stream of this instance) and
`onlineorder.push.publish_failures` (signals that could not be handed on: Redis unavailable, a full queue). The Grafana
row "Push signals (SSE)" shows them, and three alerts watch them: `OnlineOrderPushPublishFailures` (signals lost before
Redis for 10 minutes), `OnlineOrderPushSignalsUndelivered` (signals published and streams open, but none delivered for
15 minutes: no instance is subscribed to the channel) and `OnlineOrderPushStreamsRefused` (an instance answered 503 to
streams for 10 minutes). docs/operations.md section 13 has the runbook for each.
