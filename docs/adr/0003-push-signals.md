# ADR 0003: Push change signals instead of polling

- Status: **Accepted** (2026-10-22). Implementation: backend and frontend in one PR.
- Scope: the screens that poll today. These are the merchant's orders and after-sales inbox (15 s), the rider's deliveries (15 s), and the customer's notifications (15 s) and after-sales overview (10 s, only while a case is open). The admin console does not poll and is out of scope.

## Context

Every polled screen goes through `usePolling` and `useLiveResource` in `frontend/src/hooks/`. That costs two things:

- **Latency.** A merchant sees a new paid order up to 15 s late, and a rider sees a new delivery up to 15 s late.
- **Idle load.** Every open console reads the database every 10-15 s even when nothing changed.

The REST reads are correct and race-safe today. `useLiveResource` drops stale responses, and `applyAndReload` handles command results. The design keeps them as the only source of data.

## Decision

The server pushes **change signals** over **Server-Sent Events** (SSE). A signal only says "this list of yours changed". The client answers by re-reading the REST endpoint it already uses. Signals carry no data, so a lost or duplicated signal costs only a late or extra read. A slower polling fallback stays on, so nothing depends on a signal arriving.

### Transport

**Endpoint.** `GET /events/stream` returns `text/event-stream`, using Spring MVC `SseEmitter` (servlet async, so no thread is held per stream).

- It uses the session login like every other endpoint.
- An anonymous request gets 401.
- A merchant or rider login that is deactivated gets 403 when it connects (`docs/operations.md`, the active flag).

**Events.** Each signal is one SSE event, `event: signal` with `data: {"topic":"<topic>"}`. The server also sends:

- `retry: 5000` once, when the stream opens;
- a comment line `:keepalive` every 25 s.

**Deactivation.** On each keepalive the server re-checks a merchant or rider login's active flag. If the login was deactivated, it sends `event: forbidden` and completes the stream. The client then stops reconnecting and shows the console's existing 403 notice. The REST reads re-check the flag on every request anyway.

**Response headers.**
- `Cache-Control: no-cache`
- `X-Accel-Buffering: no`, so nginx and ingress do not buffer.

**Limits.** A stream ends after `app.push.stream-timeout` (30 min), and the browser reconnects on its own.

| Property | Default | Refused with |
|---|---|---|
| `app.push.max-streams-per-user` | 5 | 429 `PUSH_STREAM_LIMIT` |
| `app.push.max-streams` (per instance) | 2000 | 503 |

### Topics and recipients

| Topic | Recipient | Published after a commit that changes |
|---|---|---|
| `notifications` | `customer:{customerId}` | `order_notifications`: order, delivery and after-sales notices (`OrderNotificationService` inserts) |
| `merchant.orders` | `restaurant:{restaurantId}` | `merchant_orders`: the inbox consumer, and the merchant commands (accept, reject, preparing) |
| `merchant.after_sales` | `restaurant:{restaurantId}` | `merchant_after_sales_cases`: the projection consumer, and the merchant answers |
| `rider.deliveries` | `rider:{riderId}` | `deliveries`: assignment, reassignment (both riders), cancellation, pickup and delivery |

A stream subscribes to the recipients of its login:

- every login gets `customer:{its customer id}`;
- a merchant login also gets `restaurant:{its restaurant id}`;
- a rider login also gets `rider:{its rider id}`.

These come from the same lookups the merchant and rider endpoints use, made once at connect.

The customer's order list and after-sales overview need no topic of their own. Every change they show also writes a notification, and `OrderingPage` already re-reads both when a notification read brings a newer notification (`useNotifications`' `onNewNotifications`).

### Publishing and fan-out

**Publishing.** Writers call `PushSignals.publish(recipient, topic)` inside their transaction. It registers an after-commit callback (`AfterCommit.run`), so a rolled-back change signals nothing. Outside a transaction it publishes at once.

**Fan-out.** The writer (a Kafka consumer, say) and the open stream are usually on different instances.

- Signals go through Redis pub/sub on channel `onlineorder:push:v1`, as a JSON message `{"recipient": "...", "topic": "..."}`.
- Every instance subscribes and hands each message to its local streams for that recipient.
- `app.push.fanout=redis` is the default. `local` delivers in-process only, for tests and single-instance runs.

**Redis failure.** Publishing fails open: it logs, counts `onlineorder.push.publish_failures`, and lets the transaction stand. The clients notice nothing except that the polling fallback takes over.

**Delivery guarantee.** At most once, best effort. A stream is told nothing about signals sent while it was down. Instead the client re-reads every subscribed list on each (re)connect.

### Client

**One stream per signed-in user.** A `ServerSignals` provider in the user-keyed shell (`App.tsx`) opens one `EventSource` per signed-in user. It closes it on logout or expiry and reopens it when a different user signs in. The provider exposes:

- `connected`;
- `subscribe(topic, callback)`.

**Polling with signals.** `usePolling` gets an optional `signalTopic`:

- **Stream open:** the callback runs on every signal of that topic, plus once on every (re)connect. The interval is stretched to `PUSH_FALLBACK_POLL_MS` (60 s). Amended 2026-10-22: the callback runs at most once per `PUSH_SIGNAL_COALESCE_MS` (1 s) per topic, at once on the first signal of a quiet second and once more when that second ends for the signals it folded, after the deadline poller's cancellation of thousands of stale orders (6885 signals in 3 minutes) made a merchant console read its inbox about ten times a second, most reads aborted by the next.
- **Stream closed or failed:** the screen polls at its old interval, as today.

Commands keep `applyAndReload`. Signals never bypass `useLiveResource`, so stale responses are still dropped.

**`forbidden` event.** It closes the stream for good and lets the next read surface the 403 the consoles already handle.

**Dev and tests.**
- The dev proxy forwards `/events` (`proxyPaths.ts`).
- jsdom has no `EventSource`, so the tests use a fake one. `fakeBackend` emits the same topics on the same changes.

### Observability

| Metric | Type | Meaning |
|---|---|---|
| `onlineorder.push.streams` | gauge | Open streams on this instance |
| `onlineorder.push.signals{topic}` | counter | Signals published |
| `onlineorder.push.deliveries{topic}` | counter | Signals written to a local stream |
| `onlineorder.push.publish_failures` | counter | Failed publishes |

Ingress (`deploy/k8s/base/ingress.yaml`) needs a read timeout above the keepalive and no response buffering for `/events/stream`.

## Alternatives considered

- **WebSocket (STOMP).** It is bidirectional, but nothing here needs client-to-server messages. It would also need a second session and auth path, CSRF handling for the handshake, and more proxy configuration. SSE is plain HTTP, uses the existing session and CSP (`connect-src 'self'`), and reconnects by itself.
- **Pushing the changed data.** It would duplicate every DTO, its access checks and its read-replica rules in a second channel. Ordering against command results would also need the version logic `useLiveResource` avoids. Signals plus REST reads keep one source of truth.
- **Fan-out through Kafka, with one consumer group per instance.** It needs a group per pod, which clutters the broker and adds lag monitoring. Redis is already a dependency, and losing a signal is harmless.

## Consequences

- New pages are visible within about a second instead of up to 15 s. Idle consoles read the database once a minute instead of four to six times.
- Each open console holds one HTTP connection, about 2 per merchant tab. Tomcat's NIO connector and the per-instance cap bound it.
- A Redis outage, a proxy that buffers, or a browser without `EventSource` all degrade to slower polling, never to wrong data.
