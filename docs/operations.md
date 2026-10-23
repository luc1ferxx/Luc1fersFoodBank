# Operations

How OnlineOrder is observed, packaged, deployed and gated. Paths are relative to the repository root.

| Topic | Where |
| --- | --- |
| Tracing, logs, metrics | `backend/src/main/java/com/laioffer/onlineorder/observability/`, `application.yml` (`management.*`) |
| Local observability stack | `deploy/observability/` |
| Dashboards and alert rules | `deploy/observability/grafana/dashboards/`, `deploy/observability/prometheus/alerts.yml` |
| Kubernetes | `deploy/k8s/` (kustomize base + `staging` and `prod` overlays) |
| Read replicas (read/write splitting) | `backend/src/main/java/com/laioffer/onlineorder/datasource/`, section 11 |
| Image | `Dockerfile` |
| Time zone (the server runs in UTC) | `OnlineOrderApplication.main`, section 3 |
| CI and CD | `.github/workflows/`, `.github/dependabot.yml`, `.trivyignore` |
| Event contracts, per-aggregate order | section 8 |
| Partitions, order numbers, worker ids | section 9; the sharding plan in `docs/adr/0001-order-sharding.md` |
| Money and currencies | section 10 |
| After-sales refunds (runbook, V12 rollout, poller) | `backend/src/main/java/com/laioffer/onlineorder/aftersales/`, section 12 |
| Push signals (SSE stream, Redis channel, ingress) | `backend/src/main/java/com/laioffer/onlineorder/push/`, section 13 |

## 1. Telemetry in the application

### Traces

Micrometer Tracing with the OpenTelemetry bridge. W3C `traceparent` is read from incoming requests and written to
outgoing Kafka records.

- Every HTTP request gets a server span. The `X-Trace-Id` response header and the `trace_id` of error bodies carry
  that span's OpenTelemetry trace id. Clients join an existing trace by sending `traceparent`; a client-supplied
  `X-Trace-Id` is no longer echoed while tracing is on (it still is if tracing is switched off).
- Kafka: observation is enabled on every `KafkaTemplate` and every listener container factory (Boot's own, the
  order-event factory, the dead-letter factory and any added later, through `TracingConfig`). Producer spans inject
  `traceparent` into the record headers; consumer spans continue it.
- Outbox: the outbox table used to cut every trace in two. `outbox_events.trace_parent` (migration V6) stores the
  W3C traceparent of the span that inserted the row. `OutboxTraceContextCallback` (a Spring Data JDBC
  `BeforeConvertCallback`) fills it for every new `OutboxEventEntity` saved through `OutboxEventRepository`, whatever
  the aggregate type. The publisher sends each event inside an `onlineorder.outbox.relay` span that is a child of the
  stored context, so one trace reads:

  ```text
  http post /payments/checkout -> <topic> outbox relay -> <topic> send -> <topic> process -> consumer work
  ```

  Rows written before V6, or written outside a traced request, have no context and start a new trace. Rows inserted
  with a hand-written `INSERT` bypass the callback; insert through the repository instead. V6 only adds a nullable
  column and does not depend on V5; a database that applied V6 before V5 existed (a branch merged ahead of the one
  owning V5) fails Flyway validation once V5 arrives: start once with `SPRING_FLYWAY_OUT_OF_ORDER=true`, or recreate
  a disposable local database.
- Tracing never changes whether or how often the publisher sends an event: failures to start, scope or end a relay
  span are logged at debug level and the event is sent anyway. KafkaTemplate's own send observation belongs to
  spring-kafka; an observation handler that throws inside it fails that send like a broker error would.
- The outbox poller's own scheduled-run spans (twice a second, nothing under them) are not exported; its
  `tasks.scheduled.execution` timer is kept.
- Relay spans carry `outbox.event_id`, `outbox.aggregate_type` and `outbox.aggregate_id`, so Tempo can find the trace
  of an order: `{ span.outbox.aggregate_id = "42" }`.
- Redis: every Lettuce command is a `CLIENT` span (`db.system=redis`) of the request that issued it (new in Spring
  Boot 4). An authenticated `GET /cart` exports 9 spans instead of 5: `exists`, `hgetall`, `hmset` and `pexpireat`
  for the session; a catalog read that misses the local L1 adds the Redis cache read, a rate-limited request the
  token-bucket script. Plan Tempo storage for that (staging samples everything, prod 10%). To drop them, set
  `MANAGEMENT_OBSERVATIONS_ENABLE_LETTUCE=false`; that also removes the `lettuce_seconds` and
  `lettuce_active_seconds` timers, which no dashboard or alert reads.

Sampling (`TRACING_SAMPLING_PROBABILITY`, default 0.1) decides which new traces are recorded; downstream spans
follow the caller's decision. Trace ids exist for unsampled requests too. Spans are exported over OTLP/HTTP only when
`TRACING_EXPORT_ENABLED=true`; local runs and tests never export (`OutboxKafkaTraceContinuityTests` uses an in-memory
exporter). In Spring Boot 4 terms that is `management.tracing.export.otlp.enabled`, with the endpoint in
`management.opentelemetry.tracing.export.otlp.endpoint` (`OTEL_EXPORTER_OTLP_TRACES_ENDPOINT`). Do not set
`management.tracing.export.enabled=false` to stop exporting: it also switches W3C propagation off.

### Logs

Console logs are logstash JSON (`LOG_STRUCTURED_FORMAT_CONSOLE`). Every line written inside a span carries `traceId`
and `spanId`, so a trace id from `X-Trace-Id` finds the request's log lines and the asynchronous work it caused: the
Kafka listeners, and the outbox publisher's per-event lines ("Failed to publish outbox event ..."), which are written
with the event's relay span in scope. The publisher's run-level lines (backoff, resume) cover many events at once and
carry the trace id of the scheduled run, whose spans are not exported.

A response whose client went away while it was written (a console's read aborted by its next one, a closed tab) logs
one DEBUG line from `ApiExceptionHandler` ("Client went away while the response was written: GET /merchant/orders:
...", with the matched route template, never the raw URI) instead of `DefaultHandlerExceptionResolver`'s WARN
"Ignoring exception, response committed already"; the
request still counts in `http_server_requests`, with the status the response already carried and `exception="none"`
(the observation filter tags only an exception that escaped the dispatcher or was sent through `sendError`).

### Metrics and the management port

`MANAGEMENT_SERVER_PORT=8081` (set by the Kubernetes ConfigMap and the `app` profile of `deploy/observability`; the
image itself does not set it) moves the actuator to its own port:

| Port | Serves | Access |
| --- | --- | --- |
| 8080 | API, UI | `/actuator/**` is not served here |
| 8081 | `/actuator/health/{liveness,readiness}`, `/actuator/prometheus`, `/actuator/metrics`, `/actuator/info` | no session needed; reachable only inside the cluster (not in the Service, the Ingress or the public network) |

Without the variable (local `bootRun`, tests, a plain `docker run` of the image) the actuator stays on 8080 and
everything but `health` and `info` needs `ROLE_ADMIN`, as before. The k6 script checks `/actuator/health` on
`BASE_URL`; against a deployment with the management port, pass `MANAGEMENT_URL` too (for example
`MANAGEMENT_URL=http://localhost:8081`).

Application metrics on top of Spring Boot's (HTTP, Hikari, Tomcat, JVM, Kafka client):

| Metric (Prometheus name) | Meaning |
| --- | --- |
| `onlineorder_outbox_oldest_pending_age_seconds` | Age of the oldest PENDING/PROCESSING outbox event, 0 when none. One index entry of the V3 partial index per scrape |
| `onlineorder_outbox_backlog`, `onlineorder_outbox_events{status}`, `onlineorder_outbox_failed` | Outbox rows by status (`abandoned`: FAILED events an operator gave up on, section 8) |
| `onlineorder_outbox_publish_total{result}` | Publisher results |
| `onlineorder_outbox_blocked_aggregates`, `onlineorder_outbox_blocked_events` | Aggregates (orders, deliveries) whose later events wait behind a FAILED event of theirs, and those events (section 8) |
| `onlineorder_events_schema_violations_total{side,topic,event_type}` | Events that broke their published schema: `producer` (transaction rolled back, a bug) or `consumer` (dead-lettered without retries) |
| `onlineorder_events_stale_skipped_total{consumer,event_type}` | Events a consumer skipped because it already applied a later event of the same order |
| `onlineorder_outbox_relay_seconds` | Time from relay start until the publisher has the Kafka acknowledgement or the failure |
| `onlineorder_dead_letter_stored_total`, `onlineorder_dead_letter_events{replay_status}`, `onlineorder_dead_letter_replay_total` | Dead letters |
| `onlineorder_checkout_requests_total{result}` | Checkout results |
| `onlineorder_partitions_future{table}`, `onlineorder_partitions_count{table}`, `onlineorder_partitions_oldest_age_seconds{table}`, `onlineorder_partitions_default_rows{table}` | Monthly partitions (section 9): partitions past the current month, all range partitions, how far back the oldest starts, rows in the default partition (up to 10,001). Read from the catalog at most every 30 s; NaN on H2 |
| `onlineorder_partitions_created_total{table}`, `onlineorder_partitions_dropped_total{table}`, `onlineorder_partitions_maintenance_failures_total{table}` | Partition maintenance job |
| `onlineorder_ids_worker_id`, `onlineorder_ids_clock_backward_total{outcome}`, `onlineorder_ids_lease_lost_total` | Snowflake order numbers (section 9): the worker id in use (-1 without a lease), clock steps back waited out or refused, leases taken over |
| `onlineorder_db_routing_reads_total{target,reason}`, `onlineorder_db_replica_lag_seconds{replica}`, `onlineorder_db_replica_routable{replica}` | Read replicas (only with `DATABASE_REPLICA_URLS`; section 11) |
| `onlineorder_aftersales_refunds_total{outcome}`, `onlineorder_aftersales_cancellations_undercaptured_total`, `onlineorder_aftersales_deadlines_total{trigger,outcome}`, `onlineorder_aftersales_steps_total{action,from,to}`, `onlineorder_aftersales_merchant_decisions_total{decision}` | After-sales refunds, deadlines, case steps and merchant answers (section 12) |
| `onlineorder_security_rate_limit_rejected_total{route}`, `onlineorder_security_rate_limit_store_failure_total{route}` | Requests refused with `429 RATE_LIMITED` per bucket, and requests let through unchecked because the bucket check failed in Redis (fail open; `OnlineOrderRateLimiterFailingOpen`) |
| `onlineorder_push_streams`, `onlineorder_push_signals_total{topic}`, `onlineorder_push_deliveries_total{topic}`, `onlineorder_push_publish_failures_total`, `onlineorder_push_subscribed` | Push signals (section 13): open streams on the instance, signals published after a commit, signals written to a stream, signals that could not be handed on, 1 while the instance receives the channel's messages (its own probe comes back) |

`http.server.requests` publishes a percentiles histogram bounded to 5ms-10s plus exact SLO buckets at 50ms, 100ms,
250ms, 500ms, 1s and 2s. The latency objective reads the `le="0.5"` bucket. It and the dashboard's latency panels leave
out `/events/stream`, whose requests stay open until the stream ends (section 13).

## 2. Local observability stack

`deploy/observability/docker-compose.yml`: OpenTelemetry Collector `0.161.0`, Tempo `2.10.8`, Prometheus `v3.13.4`,
Grafana `13.0.9`.

| Service | Default host port | Override |
| --- | --- | --- |
| Grafana (anonymous viewer; admin/admin) | 3001 | `GRAFANA_PORT` |
| Prometheus | 9090 | `PROMETHEUS_PORT` |
| Tempo API | 3200 | `TEMPO_PORT` |
| OTLP gRPC / HTTP | 4317 / 4318 | `OTEL_GRPC_PORT` / `OTEL_HTTP_PORT` |
| App (profile `app`) | 8080 / 8081 | `APP_HTTP_PORT` / `APP_MANAGEMENT_PORT` |

Everything in containers (builds the root `Dockerfile` on first run, seeds the demo data):

```sh
ONLINEORDER_SCRAPE_TARGETS=./prometheus/targets/compose \
  docker compose -f deploy/observability/docker-compose.yml --profile app up -d --build
```

Stack only, app on the host next to `backend/docker-compose.yml`:

```sh
docker compose -f deploy/observability/docker-compose.yml up -d
cd backend && MANAGEMENT_SERVER_PORT=8081 TRACING_EXPORT_ENABLED=true TRACING_SAMPLING_PROBABILITY=1.0 \
  FLYWAY_LOCATIONS=classpath:db/migration,classpath:db/demo ./gradlew bootRun
```

Then place an order (UI or API), take the `X-Trace-Id` of the checkout response and open it in Grafana > Explore >
Tempo, or `curl localhost:3200/api/traces/<trace id>`. Grafana > Dashboards > OnlineOrder > "OnlineOrder - Service
overview" shows the metrics; Prometheus > Alerts shows the rules. Tear down with
`docker compose -f deploy/observability/docker-compose.yml --profile app down -v`.

Prometheus scrapes the app's management port through file service discovery: `prometheus/targets/host`
(`host.docker.internal:8081`, the default) or `prometheus/targets/compose` (`onlineorder:8081`), chosen with
`ONLINEORDER_SCRAPE_TARGETS`. Tempo's metrics generator remote-writes span metrics to Prometheus, and latency
histograms carry exemplars that link to traces.

Verified on 2026-10-16 with the app profile (Spring Boot 3.5): a checkout produced one trace in Tempo with the server
span, security spans, `order-events outbox relay`, `order-events send` and `order-events receive` (`order-events
process` since Spring Boot 4, which also adds the Redis command spans); Prometheus had all four targets up,
the onlineorder metrics above, no rule errors and no firing alerts; every dashboard query evaluated.

### Dashboard

"OnlineOrder - Service overview" (`uid: onlineorder-overview`), filterable by instance:

- Overview: request rate, 5xx ratio, share within 500ms, oldest pending outbox event, FAILED outbox events, dead
  letters in range.
- HTTP per endpoint: requests/s, 5xx ratio, p95 and p99 latency (with trace exemplars), responses by status,
  checkout results.
- HikariCP: active, idle, pending, max; acquire p99 and timeouts.
- Outbox, Kafka, dead letters: backlog by status, oldest pending age, publish results, consumer lag (max per client
  and per partition), dead letters stored and by replay status.
- JVM and Tomcat: heap, GC pause share, CPU, threads, connections.
- Rate limits: `429` refusals per bucket, and in red the requests let through because the rate-limit store failed
  (above zero means requests went through unchecked, all of them when Redis is down; `OnlineOrderRateLimiterFailingOpen`).
- Push signals (SSE): open streams per instance, signals published and delivered per topic, in red the signals lost
  before Redis, next to the streams refused with `429` or `503`, and the subscription stat: the share of the selected
  instances that receive their own push probe (section 13; `OnlineOrderPush*`).

### Alerts

`deploy/observability/prometheus/alerts.yml` (recording rules for the SLO ratios plus alerts); unit tests in
`alerts.test.yml`. Objectives: fewer than 1% of requests answer 5xx; 99% of requests finish within 500ms.

| Alert | Fires when | Severity |
| --- | --- | --- |
| `OnlineOrderTargetDown` | `up{job="onlineorder"} == 0` for 1m | critical |
| `OnlineOrderHighErrorRate` | 5xx share > 5% for 5m | critical |
| `OnlineOrderLatencySLOFastBurn` | > 14.4% of requests slower than 500ms over 1h and 5m | critical |
| `OnlineOrderLatencySLOSlowBurn` | > 6% slower than 500ms over 6h and 30m, for 15m | warning |
| `OnlineOrderOutboxBacklogStale` / `...Critical` | oldest unpublished event > 2 min / > 15 min, for 5m | warning / critical |
| `OnlineOrderOutboxEventsFailed` | any FAILED outbox event for 1m; retry with `POST /admin/outbox/events/{id}/retry` | warning |
| `OnlineOrderOutboxAggregatesBlocked` | an aggregate has later events held back behind a FAILED one, for 5m; retry it, or abandon it if it can never be published (section 8) | warning |
| `OnlineOrderEventSchemaViolations` | an event broke its schema in the last 15m, per side, including the first one after a start | warning |
| `OnlineOrderDeadLettersGrowing` | a dead letter stored in the last 15m, including the first one after a start (the counter series appears at 1); replay with `POST /dead-letters/{id}/replay` | warning |
| `OnlineOrderHikariPendingSustained` | threads waiting for a connection for 5m | warning |
| `OnlineOrderHikariConnectionTimeouts` | connection requests timed out (503) in the last 5m | warning |
| `OnlineOrderKafkaConsumerLag` | a consumer client > 1000 records behind for 10m | warning |
| `OnlineOrderRateLimiterFailingOpen` | rate-limit store failures in 8 of the last 20 half-minutes (a half-minute counts when a failure falls in the 2 minutes before it): those requests went through unchecked, all of them when Redis is down (runbook below) | warning |
| `OnlineOrderPartitionsAheadLow` / `...Missing` | fewer than 2 monthly partitions past the current month for 1h / none for 10m (section 9) | warning / critical |
| `OnlineOrderPartitionDefaultRows` | rows in a table's default partition for 1h (section 9) | warning |
| `OnlineOrderDbReplicaLagging` | a read replica > 2s behind the primary for 5m (it gets no reads meanwhile; section 11) | warning |
| `OnlineOrderDbReplicaUnavailable` | a read replica gets no reads for 5m (lagging, unreachable or not a standby) | warning |
| `OnlineOrderRefundsGivenUp` | an after-sales refund became `FAILED` in the last 15m, including the first one after a start; retry with `POST /admin/refunds/{id}/retry` (section 12) | warning |
| `OnlineOrderRefundsBackingOff` | refund claims failed (`outcome="backed_off"`) in every 5m window for 15m; a refund is given up only after 10 failed claims in a row (section 12) | warning |
| `OnlineOrderRefundGatewaySaturated` | every refund gateway thread was held by a hung call in every 5m window for 10m; no refund is claimed meanwhile (section 12, "A hung gateway") | warning |
| `OnlineOrderAfterSalesDeadlinesFailing` | after-sales deadlines failed and backed off (`outcome="failed"`) in every 5m window for 15m; their cases stay past their deadline (section 12, "Deadlines") | warning |
| `OnlineOrderCancellationsUndercaptured` | a paid order was cancelled with a refund above its captured payment in the last 15m; its refund will be given up (section 12) | warning |
| `OnlineOrderPushPublishFailures` | push signals could not be published in every 5m window for 10m; their recipients' consoles fall back to the 60 s poll (section 13) | warning |
| `OnlineOrderPushSubscriptionDown` | an instance has not received its own push probe for 5m (`onlineorder_push_subscribed` at 0): not subscribed to the Redis channel or unable to reach Redis, so it receives no signal and its consoles poll every 60 s (section 13) | warning |
| `OnlineOrderPushStreamsRefused` | an instance answered `503` to stream connects in every 5m window for 10m: at `PUSH_MAX_STREAMS`, shutting down, or its database unreachable (section 13) | warning |

On Kubernetes, load the same file into Prometheus (or wrap it in a `PrometheusRule` for the Prometheus Operator) and
make sure the scrape job is named `onlineorder` or adjust `OnlineOrderTargetDown`.

Validate after editing:

```sh
cd deploy/observability/prometheus
docker run --rm --entrypoint /bin/promtool -v "$PWD:/etc/prometheus:ro" -w /etc/prometheus prom/prometheus:v3.13.4 check rules alerts.yml
docker run --rm --entrypoint /bin/promtool -v "$PWD:/etc/prometheus:ro" -w /etc/prometheus prom/prometheus:v3.13.4 test rules alerts.test.yml
docker run --rm --entrypoint /bin/promtool -v "$PWD:/etc/prometheus:ro" -w /etc/prometheus prom/prometheus:v3.13.4 check config prometheus.yml
```

Fulfilment staffing (docs/fulfilment.md "Provisioning merchants and riders"):

- A dead-lettered `order.accepted` with `onlineorder_fulfilment_dispatch_assignments_total{result="no_rider_attempt"}`
  rising means no rider was active and on duty. Get one on duty first: the rider uses `PUT /rider/me/availability`, or
  an admin sets `PATCH /admin/riders/{id}` `{"on_duty": true}` (with `"active": true` for a deactivated rider; new and
  reactivated riders are off duty). Confirm with `GET /admin/riders`, then replay the dead letter; a replay before that
  fails again.
- Deactivating a rider leaves their open deliveries with them (`active_deliveries` in the response). List them with
  `GET /admin/deliveries?rider_id={id}` and move each with `POST /admin/deliveries/{id}/reassign` `{"rider_id": n}` to
  an active, on-duty rider, or reactivate the rider (an order still `ACCEPTED` can also be cancelled). The moves share
  the admin's `admin-deliveries` bucket (60, one more per second): a loop over more than 60 paces at one per second or
  retries a `429 RATE_LIMITED`, which moved nothing. Never clear them with the admin `delivered` endpoint: it completes
  the order.
- `onlineorder_fulfilment_merchant_orders_received_total{staffing="unstaffed"}` counts paid orders of restaurants with
  no active merchant account. They get no accept deadline and wait as `PAID` until a merchant is provisioned
  (`POST /admin/merchants`; the order is already in that restaurant's inbox) or an admin moves them.
- The accept timeout is on by default (`FULFILMENT_ACCEPT_TIMEOUT_ENABLED=true`) and only covers staffed restaurants.
- Who changed a staff account or a delivery is in `GET /admin/audit-log`: `merchant.create`, `merchant.update`,
  `rider.create`, `rider.update` and `delivery.reassign` with the admin, `rider.availability` and the rider's own
  `delivery.picked_up` / `delivery.delivered` with the rider's login (`ROLE_RIDER`).

Rate limiter failing open (`OnlineOrderRateLimiterFailingOpen`, docs/correctness.md "Security and Abuse Controls"):

- What it means: the token-bucket check of rate-limited requests (every write endpoint) failed in Redis, for some or all
  requests, in 8 of the last 20 half-minutes (a half-minute counts when a failure falls in the 2 minutes before it):
  Redis down or unreachable, slower than `REDIS_TIMEOUT` (500ms), out of memory, or refusing the bucket script. A dense
  outage fires it about 4 minutes in; sparse traffic whose rate-limited requests keep failing (one every 3 minutes)
  within about 8 minutes, since a gap between failures does not restart the count; a blip of up to about a minute and a
  half does not fire it. `RateLimitingFilter` fails open per request, so those requests reached their handlers without
  taking a token; when every check fails, login and signup have no per-IP limit meanwhile (the per-account lockout after
  failed logins still applies), nor do checkout, payment and the staff commands. The first failure of a route only
  registers its series, so one failure never fires.
- Where: `sum by (instance, route) (increase(onlineorder_security_rate_limit_store_failure_total[2m]))`, or the
  dashboard's "Rate limits" row. Every instance failing points at Redis, one instance at its network path. The WARN log
  "Rate-limit store unavailable for route ... Failing open." carries the exception (connection refused, command
  timeout, `OOM command not allowed`, `NOPERM`); a script that returned no result counts without a log line.
- Check Redis: `redis-cli PING`; `INFO memory` (`used_memory` against `maxmemory`; with `maxmemory-policy noeviction`
  a full Redis refuses the script's `HSET`); `redis-cli --latency` and `LATENCY LATEST`; `INFO clients` against
  `maxclients`; and that the application's Redis user may run `EVALSHA` and `EVAL` (`ACL GETUSER <user>`). Redis also
  holds the sessions and the catalog's L2 cache, so a full outage shows in the readiness probe and the 5xx rate too; a
  failure that only the script hits shows only here.
- It resolves about 8.5 minutes after the last failure. Nothing needs replaying: the requests let through ran normally.
  Buckets refuse again from the first successful check (a bucket whose key is gone starts full). Do not silence the
  alert with `SECURITY_RATE_LIMIT_ENABLED=false`: that removes the limits for good and hides the next failure.

## 3. Container image

The root `Dockerfile` builds the frontend in `node:24.22.0-bookworm-slim` (`npm ci`,
`npm run build`), the jar in `gradle:9.8.1-jdk17-noble` (the npm-based `buildFrontend` task is skipped there and the
stage's build output is embedded instead), extracts Spring Boot layers with `-Djarmode=tools`, and runs on
`eclipse-temurin:17.0.20.1_1-jre-noble` as uid 10001 with `JAVA_OPTS="-XX:MaxRAMPercentage=75.0
-XX:InitialRAMPercentage=50.0 -XX:+ExitOnOutOfMemoryError"` (override `JAVA_OPTS` to change them). Ports: 8080
application, 8081 management when the deployment sets `MANAGEMENT_SERVER_PORT=8081`.

`backend/Dockerfile` packages a jar built beforehand (`cd backend && ./gradlew bootJar`, then `docker build backend`)
on the same pinned Java 17 runtime, layered and non-root. Use `docker build --platform linux/amd64 backend` to build
for another architecture.

**Time zone.** The server runs in UTC: `OnlineOrderApplication.main` makes UTC the JVM's default zone before Spring
starts, so neither the host's or container's `TZ` nor `-Duser.timezone` in `JAVA_OPTS` changes it (the image sets
neither). Every timestamp is UTC wall-clock time without an offset (`LocalDateTime` in `TIMESTAMP` columns and in the
JSON), the PostgreSQL sessions of the pool and of Flyway are UTC, and so are the log timestamps; the frontend reads the
JSON's timestamps as UTC (docs/correctness.md "Timestamps and time zone"). Operator SQL that compares with the current time uses
`now() AT TIME ZONE 'UTC'` (the runbook queries) or runs with `PGTZ=UTC` (`db/ops/repair_order_history.sql`).
Rows written by a server in another zone keep that zone's wall-clock time; nothing converts them. The images already
ran in UTC, so their deployments see no change. A backend whose JVM was not in UTC (`bootRun` on a laptop in another
zone) left older rows that now read as shifted by its offset, deadlines and request windows included. Reset a local
or demo database as AGENTS.md describes (recreate `onlineorder`, or delete the `onlineorder-pg-local` volume).

```sh
docker build -t onlineorder:local .
```

## 4. Kubernetes

`deploy/k8s/base` holds the Deployment, Service (port 80 -> 8080 only), HPA, PodDisruptionBudget, Ingress
(`ingressClassName: nginx`, TLS), NetworkPolicy, ServiceAccount and a generated ConfigMap. Overlays:

| | staging | prod |
| --- | --- | --- |
| Namespace (Pod Security `restricted`) | `onlineorder-staging` | `onlineorder-prod` |
| HPA min/max | 1 / 2 | 3 / 6 |
| Resources (request / limit) | 500m, 768Mi / 2 CPU, 1Gi | 1 CPU, 1536Mi / 2 CPU, 2Gi |
| Trace sampling | 1.0 | 0.1 |
| Host | `staging.onlineorder.example.com` | `onlineorder.example.com` |

Pod details: non-root uid 10001, read-only root filesystem with an `emptyDir` on `/tmp`, all capabilities dropped,
RuntimeDefault seccomp, no service account token. Startup probe `/actuator/health/liveness` on 8081 (up to 3 minutes
for Flyway), liveness the same, readiness `/actuator/health/readiness` (readiness state, database, Redis). Shutdown:
`preStop` sleeps 10s so the endpoint removal reaches kube-proxy and the Ingress, then SIGTERM starts Spring's graceful
shutdown (`SPRING_LIFECYCLE_TIMEOUT_PER_SHUTDOWN_PHASE=30s`); `terminationGracePeriodSeconds: 60` covers both.
Prometheus scrapes pods through `prometheus.io/scrape|port|path` annotations (8081, `/actuator/prometheus`); the
NetworkPolicy opens 8081 only to the `monitoring` namespace.

PostgreSQL, Redis and Kafka are managed services. Replace the `example.internal` addresses in the overlays, then
create the Secret (never commit it; use your secret manager or External Secrets in real clusters):

```sh
kubectl -n onlineorder-staging create secret generic onlineorder-secrets \
  --from-literal=DATABASE_USERNAME=onlineorder \
  --from-literal=DATABASE_PASSWORD='<password>'
# optional, when the managed services require them:
#   SPRING_DATA_REDIS_PASSWORD, SPRING_DATA_REDIS_SSL_ENABLED=true
#   SPRING_KAFKA_SECURITY_PROTOCOL=SASL_SSL, SPRING_KAFKA_PROPERTIES_SASL_MECHANISM, SPRING_KAFKA_PROPERTIES_SASL_JAAS_CONFIG
kubectl apply -k deploy/k8s/overlays/staging
kubectl -n onlineorder-staging rollout status deployment/onlineorder
```

Keep `maxReplicas x DATABASE_POOL_MAX_SIZE` below PostgreSQL's `max_connections` (prod: 6 x 20); section 12 compares
the pool with the pod's Kafka consumer threads. Flyway migrates on startup; concurrent pods wait on Flyway's lock. Demo
seed data (`db/demo`) is not enabled in either overlay.

Validate the manifests:

```sh
kubectl kustomize deploy/k8s/overlays/staging > /tmp/staging.yaml
docker run --rm -v /tmp:/m:ro ghcr.io/yannh/kubeconform:v0.7.0 -strict -summary -kubernetes-version 1.31.0 /m/staging.yaml
```

### Environment

| Variable | Default | Where it is set |
| --- | --- | --- |
| `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD` | local PostgreSQL on 5433 | ConfigMap / Secret |
| `DATABASE_POOL_MAX_SIZE` | 30 | ConfigMap (prod 20) |
| `DATABASE_REPLICA_URLS`, `DATABASE_REPLICA_POOL_MAX_SIZE` | empty (no replicas), 20 | overlays (staging 1 replica, prod 2 with 15 each); section 11 |
| `DATABASE_REPLICA_USERNAME`, `DATABASE_REPLICA_PASSWORD` | the primary's login | Secret, only for a separate replica login |
| `REDIS_HOST`, `REDIS_PORT`, `SPRING_DATA_REDIS_PASSWORD` | localhost:6379 | ConfigMap / Secret |
| `REDIS_SESSION_NAMESPACE` | `onlineorder:session` (the session format version `:v2` is always appended) | - |
| `KAFKA_BOOTSTRAP_SERVERS`, `KAFKA_ORDER_TOPIC_REPLICAS` | localhost:9092, 1 | ConfigMap (3 replicas) |
| `MANAGEMENT_SERVER_PORT` | unset (actuator on 8080) | ConfigMap and observability compose: 8081 |
| `TRACING_EXPORT_ENABLED` | false | ConfigMap: true |
| `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` | `http://localhost:4318/v1/traces` | ConfigMap: in-cluster collector |
| `TRACING_SAMPLING_PROBABILITY` | 0.1 | overlays |
| `KAFKA_OBSERVATION_ENABLED` | true | - |
| `SESSION_COOKIE_SECURE` | false | ConfigMap: true |
| `SERVER_FORWARD_HEADERS_STRATEGY` | unset (Boot picks native on Kubernetes) | ConfigMap: native, stated explicitly (client IP for rate limits from `X-Forwarded-For`) |
| `SPRING_LIFECYCLE_TIMEOUT_PER_SHUTDOWN_PHASE` | 30s | ConfigMap |
| `SECURITY_RATE_LIMIT_ENABLED` | true (the `h2` profile turns it off) | - (`false` only for load tests: `load-tests/order-flow.js` signs up and logs in from one IP and pays faster than `payment-checkout` refills) |
| `SECURITY_RATE_LIMIT_BUCKET_TTL` | 2m (legacy name `SECURITY_RATE_LIMIT_WINDOW`): expiry of a bucket's Redis key after its last request, never shorter than the bucket's full refill plus one interval | - |
| `JAVA_OPTS` | see the image | image |
| `APP_IDS_WORKER_ID_SOURCE` | `static` (with `APP_IDS_WORKER_ID`, default 0) | ConfigMap: `lease` (section 8) |
| `CLEANUP_AUDIT_LOG_RETENTION`, `CLEANUP_ORDER_STATUS_HISTORY_RETENTION` | `0d` (kept forever) | - |
| `PARTITIONS_MONTHS_AHEAD`, `PARTITIONS_LOCK_TIMEOUT`, `PARTITIONS_MAINTENANCE_ENABLED` | 3, 5s, true | - |
| `AFTER_SALES_*` (time limits, poller, refunds, refund gateway, consumer groups), `KAFKA_AFTER_SALES_TOPIC`, `FULFILMENT_LISTENER_CONCURRENCY` | section 12 | - |
| `PUSH_FANOUT`, `PUSH_CHANNEL`, `PUSH_STREAM_TIMEOUT`, `PUSH_KEEPALIVE_INTERVAL`, `PUSH_PROBE_INTERVAL`, `PUSH_MAX_STREAMS_PER_USER`, `PUSH_MAX_STREAMS` | `redis`, `onlineorder:push:v1`, 30m, 25s, 30s, 5, 2000 (section 13) | - |

## 5. CI gates

`.github/workflows/ci.yml` (push to main/master, pull requests, and called by the release):

- `backend-test`: 4 shards, each `./gradlew test -PtestShard=i/4 -x buildFrontend -x syncFrontendBuild --no-daemon`
  on its own runner (a round-robin slice of the test classes, `backend/build.gradle`; the classes also run in 2 JVMs
  per runner). Testcontainers suites run on the runner's Docker. JUnit XML and the HTML test report of each shard are
  uploaded as `backend-test-reports-<shard>`, also on failure, and its JaCoCo file as `backend-jacoco-<shard>`.
- `backend-coverage`: downloads the shards' JaCoCo files into `backend/build/jacoco/shards` and runs
  `./gradlew jacocoTestReport jacocoTestCoverageVerification -PjacocoShards -x test ...`. The job summary shows the coverage table;
  the report is uploaded as `backend-coverage-report`.
- `backend-static`: `./gradlew spotbugsMain ...`; the report is uploaded as `backend-spotbugs-report`.
- `package-backend`: builds the jar with the embedded frontend, independently of the test jobs.
  - Coverage minimums: 87% lines, 72% branches (measured 88.6% / 73.9% with Docker available). Without Docker the
    container suites skip and the verification fails; raise the minimums as coverage grows.
  - SpotBugs scans production classes; `backend/config/spotbugs/exclude.xml` is the reviewed baseline. Its first entry
    excludes `EI_EXPOSE_REP`/`EI_EXPOSE_REP2` for every class (constructor-injected beans and records carrying
    lists, 46 of the 49 findings at introduction, and any later ones of those two patterns); the other entries are
    pattern + class. New findings of any other pattern fail the build.
- `config-lint`: promtool check and rule tests, compose config, dashboard JSON, kustomize + kubeconform for base and
  both overlays, actionlint.
- `frontend-build`: `npm ci`, `npm run typecheck` (strict TypeScript), `npm run lint`, `npm test`, `npm run build`.
  `package-backend`: unchanged.

Other workflows:

- `codeql.yml`: CodeQL `security-extended` for `java-kotlin` (compiled with Gradle) and `javascript-typescript`, on
  pushes, pull requests and weekly.
- `dependency-review.yml`: submits the Gradle dependency graph, then fails pull requests that add dependencies with
  known HIGH or CRITICAL vulnerabilities. Pull requests from forks cannot submit the Gradle graph.
- `.github/dependabot.yml`: weekly Gradle, npm, GitHub Actions and Dockerfile updates; monthly compose image updates.

Code scanning results (CodeQL, the release's Trivy SARIF) need a public repository or GitHub Advanced Security.

## 6. Release and deployment

`.github/workflows/release.yml` on a `v*` tag or manual run:

1. `ci`: the whole CI workflow.
2. `publish-image`: builds the image, runs Trivy (`HIGH,CRITICAL`, fixable only, exit code 1; accepted exceptions go
   in `.trivyignore` with a reason and `exp:` date), uploads SARIF, writes an SPDX SBOM artifact, then pushes
   `ghcr.io/<owner>/onlineorder` (tag, semver, sha, `latest` on the default branch) with SBOM and provenance
   attestations from the build cache.
3. `deploy-staging` (environment `staging`): when a `KUBE_CONFIG` secret (base64-encoded kubeconfig) exists, pins the
   pushed digest with `kustomize edit set image` and applies `deploy/k8s/overlays/staging`, then waits for the
   rollout. Without the secret it records a notice and succeeds.
4. `deploy-production` (environment `production`, `v*` tags only, after a staging deployment): the same for
   `overlays/prod`. Configure the `production` environment with required reviewers so the job waits for an approval,
   and give it its own `KUBE_CONFIG`.

Sessions across a rollout or rollback: pods of the old and the new release run side by side and share Redis.
Sessions are JDK-serialized under a versioned namespace (`onlineorder:session:v2` since Spring Boot 4; Spring Boot
3.5 used `onlineorder:session`), so releases with different session formats ignore each other's sessions instead of
failing to deserialize them (which made Spring Boot 3.5 pods answer every request with an HTML 500). Deploying the
Spring Boot 4 release, or rolling back from it, signs every user out once (JSON `401`, the UI shows the login); during
the rollout a user whose requests alternate between old and new pods is signed out each time. Nothing needs flushing:
the other namespace's keys expire after `SESSION_TIMEOUT` (30m). `RedisSessionFormatTests` fails when an upgrade
changes the classes in a stored session; bump the namespace version then (details in
`docs/spring-boot-4-migration.md`, "Sessions across releases").

State on 2026-10-16: the image scan found 45 fixable HIGH/CRITICAL findings with Spring Boot 3.5.6, pgjdbc 42.7.7
and the `17.0.20_8` base image; Spring Boot 3.5.16 and patch overrides brought that down to two accepted
spring-webmvc 6.2 exceptions (CVE-2026-47884, CVE-2026-47890), fixed only in Spring Framework 7. Since the Spring
Boot 4.1.1 migration (Spring Framework 7.0.9, see `docs/spring-boot-4-migration.md`) those are fixed and
`.trivyignore` lists nothing. Spring Boot 4.1.1 itself still manages Tomcat 11.0.24 (three CRITICAL, fixed in
11.0.25) and Jackson 3.1.5 / 2.21.5 (five HIGH, fixed in 3.1.7 / 2.21.7), so the patch overrides at the top of
`backend/build.gradle` hold Tomcat at 11.0.27, Jackson 3 at 3.1.7, Jackson 2 (swagger-core's) at 2.21.7 and
lz4-java at 1.11.4; with them Trivy 0.75.0 reports 0 for the ubuntu 24.04 base and 0 for the jars. The overrides pin
versions above what Spring Boot manages: drop each once Spring Boot manages that version or a later one, or it
holds the dependency back. `h2.version` there is not a security patch (H2 2.4.240 regression, see the comment).

## 7. Schema changes: 64-bit keys and the audit trails

Migrations V7, V7_1 and V7_2 change existing tables. All three run on PostgreSQL and on H2 (the `h2` profile runs
the same list).

**V7, 64-bit keys** (`backend/src/main/java/db/migration/V7__Widen_keys_to_bigint.java`). Every primary key and id
reference of V1-V6 (`orders.customer_id`, `order_items.cart_id`, `deliveries.rider_id`, `dispatch_orders.order_id`,
...) becomes `BIGINT`, and so do the `SERIAL` sequences behind them (`ALTER SEQUENCE ... AS bigint`, catalog only).
It is a Java migration because H2 needs `ALTER COLUMN id SET MAXVALUE` on its identity columns instead. Columns that
are already `BIGINT` are skipped. New tables use `BIGSERIAL` keys and `BIGINT` references. `LargeId*IntegrationTests`
run an order whose ids, and the ids around it, are past 2^31 through checkout, fulfilment and both audit trails;
`KeyWideningMigration*Tests` upgrade a populated V6 database whose orders sequence has run out.

Rollout caveat: on PostgreSQL `ALTER COLUMN ... TYPE bigint` rewrites the table and its indexes under an
`ACCESS EXCLUSIVE` lock (reads and writes wait) and validates the foreign keys that point at it again. Flyway runs V7
in one transaction, so every table stays locked until the whole migration commits. V7 alters each table with one
statement (one rewrite per table), which is fine for empty and small databases. For large tables, widen them online
first ("Widening large tables ahead of V7" below) and let V7 skip them. Either way the release must not run next to
pods of an older version ("Deploying the release with V7-V7_2" below).

How urgent it is: `SELECT sequencename, last_value FROM pg_sequences` against 2,147,483,647. V7 cannot be undone
safely once an id passes that value.

**V7_1, order history.** Adds `orders.updated_at` (backfilled from `created_at`) and `order_status_history`, with one
`SYSTEM`/`backfill` row per existing order. On a large `orders` table, add and backfill `updated_at` in batches
before the deploy: the migration uses `ADD COLUMN IF NOT EXISTS` and only updates rows still `NULL`. `SET NOT NULL`
then scans the table once under a lock; on PostgreSQL 12+ a validated `CHECK (updated_at IS NOT NULL)` added
beforehand lets it skip the scan.

**V7_2, audit log.** A new, empty `audit_log` table; no lock on existing tables.

**V8, staff accounts**, ships in the same release. It adds `merchant_accounts.active`/`updated_at` and
`riders.on_duty` with constant defaults (catalog only on PostgreSQL 11+), `riders.customer_id BIGINT` with a unique
constraint and a foreign key to `customers(id)`, and replaces the riders dispatch index. Both tables hold one row per
merchant or rider, so the locks are short. The no-older-pods rule below covers it too: an older pod ignores
`riders.on_duty` and would assign riders who are off duty.

Neither `order_status_history` nor `audit_log` is deleted by default: both are records to keep. Since V10 a
retention can be set for each (section 8). They grow by one row per status change and per audited admin or staff action. Query them by order
(`idx_order_status_history_order_id_id`), by target or by actor (`idx_audit_log_target_id`, `idx_audit_log_actor_id`).
Each row carries the OpenTelemetry trace id of the request or job that wrote it (`trace_id`, null when no span was
in scope), the same id as the `X-Trace-Id` header and the logs.

### Deploying the release with V7-V7_2

Roll this release out with no pod of an older version running. The Deployment's `RollingUpdate` (`maxSurge: 1`,
`maxUnavailable: 0`) keeps the old pods serving HTTP, consuming Kafka and running the deadline poller while the first
new pod migrates, and that goes wrong twice:

- V7 locks table after table inside one transaction while the old pods hold row locks. Every query on a table it
  waits for queues behind it, and an old transaction that holds a lock V7 still needs while waiting for a table V7
  already locked ends in a deadlock, which can abort the migration.
- After V7_1 commits, the old code keeps changing order status without an `order_status_history` row and without
  moving `orders.updated_at` (its UPDATE does not know the column), and the orders it places get no history at all:
  the V7_1 backfill only covers the orders that existed when it ran, so `GET /orders/{id}/history` returns `[]` for
  them.

Step 1 belongs in the release commit. Staging deploys on its own once the image is published, so scale it down
(step 2) before pushing the tag; scale production down before approving its deployment.

1. Add `"SPRING_FLYWAY_INITSQLS=SET lock_timeout = '10s'"` to the overlay's `configMapGenerator` literals. It only
   applies to Flyway's connections and can stay: when another client (a report, a backup, a `psql` session) holds a
   lock, V7 fails and rolls back after 10 s instead of queueing all traffic behind it, and the pod retries on restart.
2. Scale to zero and wait for the old pods to drain:
   `kubectl -n <ns> scale deployment/onlineorder --replicas=0`, then
   `kubectl -n <ns> wait --for=delete pod -l app.kubernetes.io/name=onlineorder --timeout=120s`. The
   HorizontalPodAutoscaler leaves a Deployment at zero replicas alone until it is scaled up again.
3. Let the release apply the new image (a rollout of zero replicas completes at once), then
   `kubectl -n <ns> scale deployment/onlineorder --replicas=1` and wait until the pod is ready: it runs V7-V7_2 alone.
4. Scale to the HorizontalPodAutoscaler's `minReplicas`; the autoscaler takes over again.
5. Check that every order has a history and that its latest row matches its status. Both counts must be 0, and stay
   0 afterwards:

   ```sql
   SELECT COUNT(*) FROM orders o
   WHERE NOT EXISTS (SELECT 1 FROM order_status_history h WHERE h.order_id = o.id);

   SELECT COUNT(*) FROM orders o
   JOIN LATERAL (SELECT h.to_status FROM order_status_history h WHERE h.order_id = o.id
                 ORDER BY h.id DESC LIMIT 1) latest ON TRUE
   WHERE latest.to_status <> o.status;
   ```

If pods of an older version did run next to the new one (a rolling update happened anyway), run
`backend/src/main/resources/db/ops/repair_order_history.sql` once the rollout has finished (usage in its header). It
adds a `SYSTEM`/`backfill` row for each order without history (`updated_at` = `created_at`, as V7_1) and for each
status change an old pod left out (`updated_at` = the repair time), and a second run changes nothing. Who made those
changes, and when, is lost. `SchemaRolloutPostgresTests` runs it against PostgreSQL.

### Widening large tables ahead of V7

Widen a large table online, one table at a time, before the release; V7 then skips its columns (it only widens
columns that are still `integer`, and the sequence). The procedure drops and recreates every constraint and index on
the swapped columns: PostgreSQL drops each index and constraint that involves a column when the column is dropped,
and the code depends on them. Without them checkout and add-to-cart fail ("there is no unique or exclusion constraint
matching the ON CONFLICT specification" for `uq_idempotency_request` and `uk_order_item_cart_menu`), and the
fulfilment consumers' target-less `ON CONFLICT DO NOTHING` silently inserts duplicates (`uq_delivery_order`: a
redelivered `order.accepted` would assign a second rider; also `uq_merchant_order_order`,
`uq_order_deadline_order_type`, `dispatch_orders_pkey`, `uq_notification_order_event`). `SchemaRolloutPostgresTests`
runs the procedure for `orders` (a key other tables reference) and `idempotency_requests` (an `ON CONFLICT` target).

0. Inventory: `psql -X -A -t -v ON_ERROR_STOP=1 -f backend/src/main/resources/db/ops/key_dependents.sql > before.txt`
   lists every constraint and index on a key column with its definition. The ones that matter for a table are its own
   rows and the foreign keys of other tables that reference it (`REFERENCES orders(id)` for `orders`:
   `fk_history_order`, `fk_notification_order`, `fk_idempotency_order`).
1. Add the new columns (catalog only), for example
   `ALTER TABLE orders ADD COLUMN id_new BIGINT, ADD COLUMN customer_id_new BIGINT, ADD COLUMN restaurant_id_new BIGINT`,
   and a `BEFORE INSERT OR UPDATE` trigger that copies each key column into its `_new` column.
2. Backfill in batches (`UPDATE orders SET id_new = id WHERE id BETWEEN ? AND ? AND id_new IS NULL`; the trigger fills
   the other columns). Then, without a long lock:
   - for each `NOT NULL` column, `ADD CONSTRAINT orders_id_new_not_null CHECK (id_new IS NOT NULL) NOT VALID` and
     `VALIDATE CONSTRAINT` it, so that `SET NOT NULL` in the swap does not scan the table under the lock;
   - for the primary key and for every index and unique constraint of the inventory, the same index on the new
     columns with `CREATE [UNIQUE] INDEX CONCURRENTLY`, the same column order and the same `WHERE` for a partial
     index: `orders_pkey_new ON orders (id_new)`, `idx_orders_customer_created_id_new ON orders (customer_id_new,
     created_at, id_new)`, `uq_idempotency_request_new ON idempotency_requests (customer_id_new, scope,
     idempotency_key)`, ...
3. Swap in one short transaction: `SET LOCAL lock_timeout = '5s'`, `LOCK TABLE` the table and the tables whose
   foreign keys reference it `IN ACCESS EXCLUSIVE MODE`, then
   - drop the referencing foreign keys of the other tables, then the table's primary key, unique constraints, foreign
     keys and plain indexes on the old columns;
   - drop the trigger: it names the `_new` columns, so after the renames every insert would fail;
   - rename each old column to `<column>_old` and each new one to `<column>`; `SET NOT NULL` on the new ones (the
     validated CHECK skips the scan; drop the CHECK afterwards) and `DROP NOT NULL` on the old ones, which new rows no
     longer fill; move the sequence, which would otherwise be dropped with the old column:
     `ALTER COLUMN id_old DROP DEFAULT`, `ALTER SEQUENCE orders_id_seq AS bigint OWNED BY orders.id`,
     `ALTER COLUMN id SET DEFAULT nextval('orders_id_seq')`;
   - put every inventory entry back under its old name: `ADD CONSTRAINT orders_pkey PRIMARY KEY USING INDEX
     orders_pkey_new`, `ADD CONSTRAINT uq_idempotency_request UNIQUE USING INDEX uq_idempotency_request_new` for each
     unique constraint, `ALTER INDEX idx_orders_customer_created_id_new RENAME TO idx_orders_customer_created_id` for
     each plain index, and each foreign key (the table's own and the referencing ones) with the inventory's definition
     plus `NOT VALID`.
4. `ALTER TABLE ... VALIDATE CONSTRAINT` each of those foreign keys (no write lock). Run the inventory again into
   `after.txt`: `diff before.txt after.txt` must print nothing, which also proves that the `ON CONFLICT` targets are
   back. Then drop the old columns.

## 8. Event contracts and per-aggregate order (V9-V9_2, V13, V14)

docs/correctness.md "Event contracts" describes the schemas, the validation and the evolution rules; this section is the operator's
side.

**Migrations.** V9 adds `outbox_events.aggregate_sequence` (nullable, no default: catalog only on PostgreSQL) and
`outbox_aggregate_sequences` (one counter per order, delivery and merchant order, kept for good), numbers the events
that are still `PENDING` or `PROCESSING` per aggregate in id order with one `MERGE` and starts each counter after them;
published rows keep `NULL` until the outbox cleanup deletes them. `FAILED` rows from before the upgrade keep `NULL` as
well: they stay what they were, terminal events that hold nothing back (the blocked-aggregate gauges ignore them), so
the upgrade does not stall the orders they belong to. Triage them before or after the upgrade as before (`GET
/admin/outbox/events/failed`, rows with `aggregate_sequence` null); a retry publishes such a row unordered, as before
V9, and its consumers apply it under the out-of-order rules (docs/fulfilment.md "Out-of-order events"). V9_1 builds the unique
partial index `idx_outbox_events_unpublished_aggregate (aggregate_type, aggregate_id, aggregate_sequence) WHERE status
IN ('PENDING', 'PROCESSING', 'FAILED')` on PostgreSQL (H2 only records the version). Like V3 it is a plain `CREATE
INDEX` in Flyway's transaction: it scans the table and blocks outbox writes (checkouts, status changes) meanwhile, about
a second per million rows. V9_2 adds `consumer_aggregate_sequences`, empty. Roll the release out without pods of an
older version (section 7's procedure): an older pod writes events without a sequence (the claim publishes them
unordered) and without schema validation. Pods of this release read events written by older pods (no `sequence`) and the
reverse (older consumers ignore the new field).

**Claim fencing (V13).** V13 adds `outbox_events.claim_token` (nullable, no default: catalog only on PostgreSQL) and
the CHECKs `chk_outbox_events_status` (`PENDING`, `PROCESSING`, `PUBLISHED`, `FAILED`, `ABANDONED`) and
`chk_dead_letter_events_replay_status` (`PENDING`, `REPLAYING`, `REPLAYED`, `REPLAY_FAILED`). Each `ADD CONSTRAINT`
checks the existing rows under the table's `ACCESS EXCLUSIVE` lock, so outbox writes (checkouts, status changes) wait
for the scan of `outbox_events`, about as long as V3's and V9_1's index builds (a second per million rows); published
rows are kept for `app.cleanup.outbox-retention` (7 days). Check first that no row has another status:

```sql
SELECT status, COUNT(*) FROM outbox_events GROUP BY status;
SELECT replay_status, COUNT(*) FROM dead_letter_events GROUP BY replay_status;
```

A claim of this release writes a new token, and its outcome (published, released, retry, failed) changes a row only
while it is still `PROCESSING` under that token: a publisher whose claim expired mid-batch can no longer put an event
that another publisher published back to `PENDING` (sending it again) or make it `FAILED` (blocking its aggregate), nor
release another publisher's claim. Such a late write changes nothing and logs the WARN "Outbox <write> changed n of the
events [ids]; the others were no longer held by this publisher's claim"; several of them mean
`app.outbox.claim-timeout` is too short for the publish timeout (the startup WARN about `claim-timeout` says so too).
While pods of the previous release still run next to this one, their claims write no token and their outcomes stay unfenced, as before: they behave as they do
today, and a row they claimed keeps the token of an earlier claim of this release, if any, so a late write under that
token still applies. The fencing is complete once no older pod publishes.

**Order events v2 and V14.** This release writes the six order events as version 2 (`ORDER_EVENT_VERSION` in
`OrderEventOutboxService`), a `BACKWARD` version that adds the optional `data.order_no`, the order's public number as a
string. V14 adds `merchant_orders.order_no` and `deliveries.order_no` (`BIGINT`, nullable, no default, no foreign key
or unique constraint: catalog only on PostgreSQL, a brief `ACCESS EXCLUSIVE` lock on each table, no rewrite). The
merchant inbox and dispatch store the number with the rows they create and fill it into a row that has none from any
later event that carries it, a stale one included (the number never changes); they never replace one. The merchant
inbox, the rider's deliveries and the admin delivery list return it as `order_no` after `order_id`. Nothing backfills
the existing rows: they keep `NULL`, and the consoles show the order id for them.

Pods of the previous release may run next to this one; the rollout needs no scale to zero:

- A consumer of the previous release knows order events up to v1. `EventContracts.read` checks an event of a newer
  version against the latest version it knows (`EventSchemaRegistry.validate`), so it checks a v2 event against v1,
  which accepts it: the schemas are open and v2 only adds an optional property (`EventSchemaContractTests` checks the
  v2 samples against v1). It then maps the event onto its own payload record, which has no `order_no`; the application
  mapper ignores unknown properties. Nothing is dead-lettered, and `order_no` is dropped there
  (`OrderEventVersionRolloutTests`).
- Producers of the previous release keep writing v1 events, which this release reads as events without a number. An
  inbox row or delivery that a v1 event, or a consumer of the previous release, created has no number until a later v2
  event fills it. The merchant inbox sees every order event, so its open rows fill at the order's next step; dispatch
  only handles `order.accepted` and `order.cancelled`, so such a delivery keeps showing the id unless it is cancelled.
- A rollback leaves the two columns in place: the previous release neither writes nor reads them, and reads the v2
  events still in the outbox, on the topics or among the dead letters as above. Rows it creates meanwhile have no
  number.

Demo runs need none of this: the demo callback `db/demo/afterMigrate__backfill_order_numbers.sql` fills every row that
has none at each start with `db/demo`. To give the open rows of the rollout their numbers at once, an operator may run
this once after the rollout. It reads
`orders` from the fulfilment tables, which the application never does, so it only works while they share one
database (ADR 0001); the open rows are few, but run it in a quiet period:

```sql
UPDATE merchant_orders m SET order_no = o.order_no
FROM orders o
WHERE o.id = m.order_id AND m.order_no IS NULL AND m.status IN ('PAID', 'ACCEPTED', 'PREPARING');
UPDATE deliveries d SET order_no = o.order_no
FROM orders o
WHERE o.id = d.order_id AND d.order_no IS NULL AND d.status IN ('ASSIGNED', 'PICKED_UP');
```

**A blocked aggregate** (`OnlineOrderOutboxAggregatesBlocked`): the publisher only claims the head of each aggregate,
so a `FAILED` event holds back the later events of its order or delivery, on every topic it writes to, until it is
published or abandoned. Find it with `GET /admin/outbox/events/failed` (`aggregate_type`, `aggregate_id`,
`aggregate_sequence`, `last_error`), fix the cause (a broker ACL, a topic that was missing, ...), then
`POST /admin/outbox/events/{id}/retry`: it goes back to `PENDING` with its attempts reset, and the held-back events
follow in order once it is published (within the same publisher run: the publisher claims an aggregate's next event
as soon as the previous one is acknowledged). To see what waits behind it:

```sql
SELECT id, event_type, aggregate_sequence, status
FROM outbox_events
WHERE aggregate_type = 'ORDER' AND aggregate_id = <id> AND status <> 'PUBLISHED'
ORDER BY aggregate_sequence;
```

Never set a held-back event to `PUBLISHED` by hand to skip the failed one: its consumers would then apply the order's
later events without the earlier one.

**An event that can never be published** (the broker will always reject the record, for example one over its size
limit): abandon it as a last resort, with the reason and how its effect is reconciled:

```sh
curl -sf -X POST "$BASE_URL/admin/outbox/events/<id>/abandon" -b "$ADMIN_COOKIE" \
  -H 'Content-Type: application/json' -d '{"reason": "record over the broker limit; order 42 cancelled by hand"}'
```

Only a `FAILED` event can be abandoned. It becomes `ABANDONED` (`onlineorder_outbox_events{status="abandoned"}`): never
published, never retried, never deleted by the outbox cleanup, `last_error` kept, and the audit log has
`outbox_event.abandon` with the reason, the attempts, the error and how many held-back events it released. `ABANDONED`
is outside the statuses the claim waits for, so the aggregate's later events are published next, in order. Its
consumers never receive it: what it would have caused does not happen (a merchant inbox entry for `order.paid`, a
dispatch for `order.accepted`, a completion for `delivery.delivered`, ...), the later events are applied without it,
and the consumers that track sequences only see a gap, which they accept. Reconcile that by hand, typically by
cancelling or completing the order through the admin API. The list of abandoned events:

```sql
SELECT id, aggregate_type, aggregate_id, aggregate_sequence, event_type, last_error, updated_at
FROM outbox_events WHERE status = 'ABANDONED' ORDER BY updated_at;
```

**Schema violations** (`OnlineOrderEventSchemaViolations`):

- `side="producer"`: the application built an event its contract does not allow; the transaction that wrote it was
  rolled back and the request answered 500. The ERROR log line has the event id, type, version and the violations. It
  is a bug in the producer or a schema change that was not published (`event-schemas/catalog.json`).
- `side="consumer"`: a record on the topic does not match its contract (a producer outside this application, a
  hand-made record, a version this instance cannot read). Each consumer group dead-lettered it on the first attempt;
  its `dead_letter_events` row (by `event_id`) has the violations in `error_message`. Replaying it
  (`POST /dead-letters/{id}/replay`) fails the same way until the consumers know its version: deploy them first, then
  replay.

**Stale events.** `onlineorder_events_stale_skipped_total{consumer}` rising after a dead-letter replay is expected:
the replayed event was older than what the merchant inbox, dispatch or timeout consumer already applied for the order.
Their positions live in `consumer_aggregate_sequences` and are deleted with `processed_events`
(`app.cleanup.processed-event-retention`).

**Sequence counters.** `outbox_aggregate_sequences` keeps one row per aggregate that ever had an event (about three per
order: the order, its delivery, its merchant order), so it grows with `orders` and nothing deletes it. Its id is drawn
from a sequence on every event (the upsert draws one even when it updates), so ids grow with the number of events;
`BIGINT` leaves ample room. A counter may only be deleted when restarting it at 1 cannot make a consumer skip new events
as stale: the aggregate has no outbox row left that is not `PUBLISHED`, no consumer position in
`consumer_aggregate_sequences`, no stored dead letter, and its last event is older than the longest of
`app.cleanup.outbox-retention`, `app.cleanup.processed-event-retention` and `app.cleanup.dead-letter-retention`. There
is no cleanup job for it yet; archive or partition it together with `orders` when that becomes necessary.

**Mirroring the schemas into a schema registry.** The application validates against the schemas it ships and needs no
registry. To make them discoverable for other teams, publish each file under a subject per event type, for example
`order-events-order.paid`, with its `$id` as the reference name, from CI after the release:

```sh
# Confluent Schema Registry (JSON Schema support)
curl -sf -X POST "$REGISTRY/subjects/order-events-order.paid/versions" \
  -H 'Content-Type: application/vnd.schemaregistry.v1+json' \
  -d "$(jq -n --rawfile s backend/src/main/resources/event-schemas/order-events/order.paid/v1.json \
        '{schemaType: "JSON", schema: $s}')"
# Apicurio Registry 3
curl -sf -X POST "$REGISTRY/apis/registry/v3/groups/onlineorder/artifacts" -H 'Content-Type: application/json' \
  -d "$(jq -n --rawfile s backend/src/main/resources/event-schemas/order-events/order.paid/v1.json \
        '{artifactId: "order-events-order.paid", artifactType: "JSON", firstVersion: {content: {content: $s, contentType: "application/json"}}}')"
```

Publish `common/` first (the event schemas `$ref` it; register it as a reference) and set the subject's compatibility
to `FULL_TRANSITIVE` (Confluent) or the `COMPATIBILITY` rule `FULL_TRANSITIVE` (Apicurio): a `BACKWARD` version here is
compatible both ways with the earlier versions of its major line, as `EventSchemaContractTests` checks. A `BREAKING`
version starts a new major line, so register it under a new subject (`order-events-order.paid-v2`) instead of
loosening the rule. Records stay plain JSON without a registry schema id, so consumers keep working whether or
not a registry is there.

## 9. Data tier: monthly partitions, order numbers and worker ids

Migrations V10, V10_1 and V10_2 (Java migrations in `backend/src/main/java/db/migration`) prepare the database for
growth without sharding it; the sharding plan, and why it waits, is
[docs/adr/0001-order-sharding.md](adr/0001-order-sharding.md). They run on PostgreSQL and H2; only V10 differs.

### Monthly partitions (V10)

On PostgreSQL the append-only tables that grow with traffic are range-partitioned by month:

| Table | Partition column | Retention | Default |
| --- | --- | --- | --- |
| `processed_events` | `processed_at` | `CLEANUP_PROCESSED_EVENT_RETENTION` | 14d |
| `audit_log` | `occurred_at` | `CLEANUP_AUDIT_LOG_RETENTION` | 0d: kept forever |
| `order_status_history` | `occurred_at` | `CLEANUP_ORDER_STATUS_HISTORY_RETENTION` | 0d: kept forever |

Partitions are named `<table>_pYYYY_MM` and cover `[first of the month, first of the next month)` of the column, in
the time the application writes (`LocalDateTime.now()`, UTC: section 3); `<table>_default` takes rows
outside every partition. V10 copies each table into its partitioned replacement with its ids, keys, checks, the
history's foreign key to `orders` and the indexes, and creates the months that have rows, the current month and the
next three. H2 keeps plain tables (the `h2` profile deletes rows instead). `outbox_events` is not partitioned yet; it
is the next candidate (partition by `created_at`, drop published months).

What partitioning changes, because a primary key or unique constraint of a partitioned table must contain the
partition column:

- The primary keys are `(id, occurred_at)` / `(id, processed_at)`. Ids still come from one sequence per table and stay
  unique; nothing references these tables by id.
- `processed_events` no longer has `UNIQUE (consumer_name, dedup_key)` across the table: the same event processed
  again in a later month (a dead letter replayed weeks later) would land in another partition. Every consumer's mark
  goes through `ProcessedEventRepository.insertIfAbsent` (`ProcessedEventDedupImpl`), which takes
  `pg_advisory_xact_lock` on the key in the consumer's transaction and inserts only if no partition has the key; a
  second delivery waits for the first transaction and then sees its row, as with a unique index. Each partition also
  has `UNIQUE (consumer_name, dedup_key)` (`<partition>_dedup_key`), which serves that lookup and rejects a duplicate
  within a month at the database level. `ProcessedEventDedupAcrossPartitionsPostgresTests` covers replays across
  months, concurrent deliveries (also two that fall into different months, which only the advisory lock keeps apart),
  waiting and rollback.
- Queries that do not filter on the partition column probe every partition. `GET /orders/{id}/history` filters on the
  order's creation time (minus a day of clock skew), so it skips the months before the order.

`PartitionMaintenanceService` runs hourly (`PARTITIONS_FIXED_DELAY_MS`, first run a minute after start) on every
instance; each step takes `pg_try_advisory_xact_lock` for its table, so one instance does the work and the others
skip. It keeps the current month and `PARTITIONS_MONTHS_AHEAD` (3, at least 2) months ahead, moving rows that already
sit in the default partition into a new month's partition; gives every `processed_events` partition its dedup index;
and, when `CLEANUP_ENABLED=true`, drops every partition whose upper bound is older than the table's retention and
deletes expired rows from the default partition in batches. A row is kept for at least its retention and at most a
month longer. Creating or dropping a partition takes its table locks before any DDL, in the order the application
takes them: for a new `order_status_history` partition first `orders` (SHARE ROW EXCLUSIVE, for the foreign key),
then the parent table alone and its default partition (ACCESS EXCLUSIVE). Reads and writes of the table wait for a
moment; when rows move out of the default partition they wait until the move commits, then go to the new partition,
so a row written meanwhile is neither lost nor left in the default partition, and the job cannot deadlock with a
status change that holds its order. `PARTITIONS_LOCK_TIMEOUT` (5s) makes the step give up instead of queueing every
insert behind a long-running query, and the next run retries.
`MaintenanceCleanupService` skips the partitioned tables. Turning `PARTITIONS_MAINTENANCE_ENABLED` off also stops
retention for them.

Alerts and what to do:

- `OnlineOrderPartitionsAheadLow` (fewer than 2 future partitions for 1h) / `OnlineOrderPartitionsAheadMissing` (none,
  10m; next month's rows would land in the default partition): find "Partition maintenance of ... failed" in the logs
  (`onlineorder_partitions_maintenance_failures_total`). A lock timeout passes with the next run; a permission error
  means the database user does not own the table (creating and dropping partitions needs ownership). To bridge by hand:

  ```sql
  CREATE TABLE audit_log_p2027_02 PARTITION OF audit_log
      FOR VALUES FROM ('2027-02-01 00:00:00') TO ('2027-03-01 00:00:00');
  -- processed_events partitions also need their dedup index:
  CREATE UNIQUE INDEX processed_events_p2027_02_dedup_key ON processed_events_p2027_02 (consumer_name, dedup_key);
  ```

- `OnlineOrderPartitionDefaultRows`: rows outside every partition, usually from a wrong clock (far past or future) or
  a month the job missed. The job moves them when it creates their month; rows of a month it will not create (the
  past) stay until the retention deletes them. To give a past month its partition by hand, do what the job does, in
  one transaction and in this order (a copy followed by a separate delete, without the locks, deletes rows that other
  transactions commit in between):

  ```sql
  BEGIN;
  SET LOCAL lock_timeout = '5s';
  LOCK TABLE orders IN SHARE ROW EXCLUSIVE MODE;          -- order_status_history only
  LOCK TABLE ONLY audit_log IN ACCESS EXCLUSIVE MODE;
  LOCK TABLE audit_log_default IN ACCESS EXCLUSIVE MODE;
  CREATE TABLE audit_log_p2026_01 (LIKE audit_log INCLUDING DEFAULTS INCLUDING CONSTRAINTS);
  WITH moved AS (
      DELETE FROM audit_log_default
      WHERE occurred_at >= '2026-01-01 00:00:00' AND occurred_at < '2026-02-01 00:00:00'
      RETURNING *
  )
  INSERT INTO audit_log_p2026_01 SELECT * FROM moved;
  ALTER TABLE audit_log ATTACH PARTITION audit_log_p2026_01
      FOR VALUES FROM ('2026-01-01 00:00:00') TO ('2026-02-01 00:00:00');
  COMMIT;
  ```

Rollout and large tables: V10 copies the three tables inside Flyway's transaction and holds an ACCESS EXCLUSIVE lock
on each until the migration commits, so checkouts, status changes and consumers wait for it. Measured on PostgreSQL 16
in Docker on a laptop: 900k rows (200k history, 200k audit, 500k processed events) in 2.7 s. For a large table, partition it by hand ahead of the release; V10 skips tables that are already
partitioned. The outline (not exercised by the test suite): add `CHECK (<column> < '<first of next month>') NOT VALID`
and validate it, build `UNIQUE (id, <column>)` with `CREATE UNIQUE INDEX CONCURRENTLY`, then in one short
transaction rename the table to `<table>_legacy`, create the partitioned table as V10 does (same columns, primary key
`(id, <column>)`, the sequence moved over), attach `<table>_legacy` `FOR VALUES FROM (MINVALUE) TO ('<first of next
month>')` (the validated check lets the attach skip its scan), and create the next months and the default
partition. Retention later drops the legacy partition as a whole once its upper bound is past the retention. For
`processed_events`, rename the legacy table's unique index to `processed_events_legacy_dedup_key`, so the job does not
build a second one.

### Order numbers (V10_1)

`orders.order_no` (`BIGINT NOT NULL`, `uq_orders_order_no`) is the public order number: a 64-bit Snowflake id
(`SnowflakeIdGenerator`: 41 bits of milliseconds since 2020-01-01, 10 bits of worker id, 12 bits of sequence starting
at a random value below 2048 each millisecond). Checkout assigns it (`OrderNumberCallback`, on the insert of a new
order); the order APIs return it as `order_no`, a JSON string because JavaScript numbers lose digits past 2^53;
notifications name the order by it, and so does the customer UI (`orderLabel` in `frontend/src/lib/labels.ts`; the
merchant and rider consoles show the copy that the order events bring to their tables since V14, section 8, and the
internal id for rows without one, see docs/limitations.md "Honest Boundaries"). The internal `id` stays the key of
every table and API path. Order numbers do not
reveal how many orders exist, but they are not secrets: every endpoint still checks who may read the order.

V10_1 numbers the existing orders in id order from their `created_at`, with worker id 1023, which no running instance
uses, so backfilled and live numbers cannot collide. It updates every order row in batches of 1,000 inside Flyway's
transaction (200k orders in 4.5 s in the same setup, holding their row locks until the commit); on a very large
`orders` table run the release in a quiet period. Like V7-V7_2 it must not run next to
pods of an older version: those insert orders without a number, which `order_no NOT NULL` rejects (follow "Deploying
the release with V7-V7_2" in section 7). An insert by hand must set `order_no` too.

### Worker ids (V10_2)

Every instance that issues order numbers at the same time needs its own worker id (0-1022); two instances with the
same id can produce the same number, and checkout then fails on `uq_orders_order_no` instead of creating a duplicate.

| `APP_IDS_WORKER_ID_SOURCE` | Worker id | Use |
| --- | --- | --- |
| `static` (default) | `APP_IDS_WORKER_ID` (default 0) | one local instance, tests, the `h2` profile; a StatefulSet that sets `APP_IDS_WORKER_ID` from its pod index (`fieldRef: metadata.labels['apps.kubernetes.io/pod-index']`, Kubernetes 1.28+) |
| `lease` | leased from `snowflake_worker_leases` | the Kubernetes Deployment (ConfigMap): pods come and go with rollouts and the autoscaler and have no stable ordinal |

Lease mode (`WorkerIdLeaseManager`, PostgreSQL only): on start the pod claims the lowest free row (`FOR UPDATE SKIP
LOCKED`) and holds it for `APP_IDS_LEASE_TTL` (60s) of database time, renewed every `APP_IDS_LEASE_RENEW_INTERVAL`
(15s) on a thread of its own; a graceful shutdown releases the row after the web server and the Kafka listeners have
stopped, a crashed pod's row is free after the TTL. If no row is free the pod does not start. Each row also stores
`issued_until_ms`, a bound on the timestamps of the ids issued under it: the holder never issues past it and raises it
only after the database has stored the new value, and the next holder starts after it (waiting up to one TTL if its
clock is behind). A free row whose bound is more than one TTL ahead of the pod's clock (its last holder's clock ran
fast) is claimed only when no other row is free, and then handed back with a start failure; otherwise the pod takes
the next free row. So a pod that stalls past its lease cannot collide with the pod that took its worker id over, and a
pod that cannot renew (database unreachable) stops issuing about one TTL after its last renewal: checkout answers
`503 SERVICE_UNAVAILABLE` until the database is back. A renewal that finds the row taken over leases another one
(`onlineorder_ids_lease_lost_total`).

Clock steps: the generator never reuses a timestamp. A step back of up to `APP_IDS_MAX_CLOCK_BACKWARD` (50ms) is waited
out; a larger one makes checkout answer 503 until the clock has caught up
(`onlineorder_ids_clock_backward_total{outcome="refused"}`). Keep the nodes on NTP with slewing.

Inspect the leases with `SELECT worker_id, owner, lease_until, issued_until_ms FROM snowflake_worker_leases WHERE owner
IS NOT NULL ORDER BY worker_id`. Several clusters sharing one database share the table as well; clusters with their
own databases but one Kafka topology and shared order numbers would need disjoint static ranges instead.

## 10. Money with currency (V11)

V11 (`backend/src/main/java/db/migration/V11__Money_with_currency.java`) gives every amount a currency and a fixed
scale; docs/correctness.md "Money and currency" has the model. It creates the `currencies` reference table, adds
`currency CHAR(3) NOT NULL DEFAULT 'USD'` (checked to be three upper-case letters) to `restaurants`, `menu_items`,
`carts`, `order_items`, `orders`, `order_history_items` and `merchant_orders`, turns every amount into `NUMERIC(12,2)`
with a non-negative check, makes `restaurants.currency` reference `currencies`, and adds the composite keys that tie a
menu item to its restaurant's currency and a cart line to its menu item's and its cart's.

**Supported currencies.** `currencies (code, minor_units)` holds the currencies the `Money` type accepts, with their
ISO 4217 minor units: USD, EUR, GBP, CHF, CAD, AUD, NZD, SEK, NOK, DKK, PLN, CZK, HUF, CNY, HKD, SGD, TWD, INR, THB,
MXN, BRL, ZAR and AED (2), JPY and KRW (0). A restaurant can only use one of them, and a menu item in JPY or KRW only
a whole price (`chk_menu_item_price_minor_units`), so no row created by SQL can make add-to-cart or checkout fail
inside `Money`. Codes `Money` rejects cannot be added either: the table only takes 2 minor units, or 0 for JPY and
KRW, so BHD or KWD (3), XAU or XXX (none) and invented codes stay out. To support another currency, add a migration
that inserts its row with the minor units `java.util.Currency#getDefaultFractionDigits` reports; one without minor
units (VND, CLP, ...) also has to replace `chk_currency_minor_units` and `chk_menu_item_price_minor_units`, which
name JPY and KRW. `MoneyCurrencyMigration*Tests` check every row against `Money`.

**Data check.** V11 first checks every existing amount, unit prices (`menu_items`, `order_items`,
`order_history_items`) and totals (`carts`, `orders`, `merchant_orders`) alike, and stops before changing anything
when one has more than two decimal places or exceeds 9,999,999,999.99. The error names each such column with its row
count and the ids of its first ten rows. V11 never rounds an amount: the previous releases stored prices as given and
summed totals exactly in `NUMERIC`, so an amount with sub-cent digits was edited by hand or is otherwise
inconsistent. Find the rows, decide the right value (for a total, usually the sum of its lines), fix them, and start
the release again (on PostgreSQL the failed migration rolled back completely).

```sql
SELECT 'menu_items' AS source, id, price AS amount FROM menu_items
WHERE price <> ROUND(price, 2) OR ABS(price) > 9999999999.99
UNION ALL
SELECT 'order_items', id, price FROM order_items WHERE price <> ROUND(price, 2) OR ABS(price) > 9999999999.99
UNION ALL
SELECT 'order_history_items', id, price FROM order_history_items
WHERE price <> ROUND(price, 2) OR ABS(price) > 9999999999.99
UNION ALL
SELECT 'carts', id, total_price FROM carts WHERE total_price <> ROUND(total_price, 2) OR ABS(total_price) > 9999999999.99
UNION ALL
SELECT 'orders', id, total_price FROM orders WHERE total_price <> ROUND(total_price, 2) OR ABS(total_price) > 9999999999.99
UNION ALL
SELECT 'merchant_orders', id, total_price FROM merchant_orders
WHERE total_price <> ROUND(total_price, 2) OR ABS(total_price) > 9999999999.99;

-- An order's total against the sum of its lines (a cart's: SUM over order_items with cart_id = carts.id).
SELECT o.id, o.total_price, SUM(i.price * i.quantity) AS lines_total
FROM orders o JOIN order_history_items i ON i.order_id = o.id
GROUP BY o.id, o.total_price
HAVING o.total_price <> SUM(i.price * i.quantity);
```

**Locks.** On PostgreSQL V11 starts with one `LOCK TABLE restaurants, menu_items, carts, order_items, orders,
order_history_items, merchant_orders IN ACCESS EXCLUSIVE MODE`, before the data check, so that no row changes between
the check and the type change; reads and writes on those tables wait from then until V11 commits. Changing `NUMERIC`
to `NUMERIC(12,2)` then rewrites the six tables with an amount, and the new checks and keys scan them again; adding
the currency columns themselves is catalog-only. `LOCK TABLE` still takes the locks one table at a time, in that
order. With a `lock_timeout` (rollout step 1) a lock that another session holds makes V11 fail after the timeout and
roll back, releasing the tables it already had, before it has changed anything; the pod retries when it restarts
(`MoneyCurrencyMigrationPostgresTests`). Without one, V11 waits, and all traffic on the tables it has locked queues
behind it.

The migration must also finish within the pod's startup allowance (`startupProbe`: 36 failures x 5 s, about three
minutes). A pod killed during V11 rolls it back and the next pod starts over, so a rewrite that takes longer never
completes. Time V11 against a restored copy of production first (sizes:
`SELECT relname, pg_size_pretty(pg_total_relation_size(oid)) FROM pg_class WHERE relname IN ('orders',
'order_history_items', 'order_items', 'merchant_orders')`); if it takes more than about two minutes, raise the
overlay's `startupProbe.failureThreshold` for this release and plan a maintenance window.

**Rollout.** Roll V11 out like V7: no pod of the previous release may run next to it. Follow steps 1-4 of "Deploying
the release with V7-V7_2" (`SPRING_FLYWAY_INITSQLS` with a `lock_timeout`, scale to zero, one replica that migrates
alone, then the autoscaler's `minReplicas`). Under the default `RollingUpdate` the old pods keep serving while V11
runs, and that goes wrong:

- Deadlock: an old pod's add-to-cart locks the cart row (`FOR UPDATE`) and then reads `menu_items`, while V11 holds
  `menu_items` and waits for `carts`. PostgreSQL aborts one of the two, the customer's request or the migration.
- Queueing: while V11 waits for or holds its locks, every request on those seven tables waits too.
- After V11 commits, the old pods' server-side prepared statements on the altered tables fail once per connection
  ("cached plan must not change result type").

A rollback to the previous release after V11 works while every restaurant prices in USD (it writes no currency, and
the defaults are USD). It would refuse cart lines of a restaurant in another currency and check such carts out as USD
orders, so add the first such restaurant only once a rollback past this release is no longer planned. The catalog
caches moved to key version `v2`, so the two releases never share entries, and the previous release ignores the new
`data.currency` of order events (consumers of this release read an event without one as USD).

**A restaurant in another currency.** There is no API to create restaurants: insert it with a `currency` from the
`currencies` table, and its menu items with the same `currency` (the composite key refuses another) and, for JPY or
KRW, whole prices. A restaurant's currency cannot change while it has menu items, and a cart's not while it has
lines; repricing a restaurant into another currency means replacing its menu items, and carts that hold them must be
emptied first.

## 11. Read replicas

Read/write splitting onto PostgreSQL streaming replicas (hot standbys), in
`backend/src/main/java/com/laioffer/onlineorder/datasource/`. It is off unless `DATABASE_REPLICA_URLS` lists a
replica: then nothing of it exists and every statement uses the single primary pool, as before. Local runs, tests and
the base Kubernetes manifests leave it off; the staging and prod overlays turn it on.

### What reads a replica

The application's `DataSource` becomes `LazyConnectionDataSourceProxy -> ReadWriteRoutingDataSource -> primary pool |
replica pools`. A transaction manager asks for its connection when the transaction begins, before Spring marks it
read-only; the lazy proxy defers the physical connection to the first statement, when `ReplicaRouter` decides:

- **Replica**: a read-only transaction declared by application code, `@Transactional(readOnly = true)` on a service
  method or `ReadOnlyTransactions.read(...)`. These are deliberately chosen read paths: catalog loads behind the cache
  (cache misses only; a hit opens no transaction), `GET /orders`, `GET /orders/{id}/history`, `GET /notifications`,
  the merchant inbox (`GET /merchant/orders`), the rider's deliveries (`GET /rider/deliveries`), the admin lists
  (`/admin/audit-log`, `/admin/merchants`, `/admin/riders`, `/admin/deliveries`) and the after-sales reads
  (`GET /after-sales/cases`, `/after-sales/cases/{caseId}`, `/merchant/after-sales`, `/admin/after-sales`,
  `/admin/after-sales/{caseId}`, `/admin/refunds`; section 12). The merchant's and rider's active check runs before the
  read-only part, outside it, so a deactivation takes effect on the next request whatever the lag.
- **Primary**: everything else. Write transactions and everything in them: row locks (`FOR UPDATE`), idempotency
  checks, outbox claims and publishing, consumers, the deadline poller, DLT replay. Statements outside a transaction:
  login (`JdbcUserDetailsManager`), the customer lookup every controller does, the health check, the outbox metrics.
  Spring Data's implicit read-only transactions around CRUD finders called outside a transaction (`findById`,
  `existsById`, `findAll*`, `count`): the router recognises them by transaction name, so a stray finder in write-path
  code never reads a lagging replica. Flyway migrates the primary over its own unpooled connections. Spring Session
  lives in Redis and never touches the database. The outbox and dead-letter admin lists stay on the primary too: they
  are the tools to repair exactly that state.

Why locks, idempotency and claims never go to a replica: a standby cannot take row locks or write at all, and a
decision taken on data up to `max-lag` old (is this idempotency key used? is this order still `PAID`? is this outbox
event still `PENDING`?) would be wrong. Each of them runs inside the write transaction that acts on it, on the primary.
A write or `SELECT ... FOR UPDATE` that slips into a read-only transaction fails loudly (PostgreSQL rejects it in a
read-only transaction, on the primary as on a standby) instead of silently reading stale data.

### Read-your-writes

After a request commits a write transaction (`ReadYourWritesTracker`, a `TransactionExecutionListener`), the time is
stored in the HTTP session and on the request. For `sticky-window` after that, the session's read-only transactions
use the primary. The session lives in Redis, so the window holds on every pod. Writes outside an HTTP request
(consumers, pollers) have no session; reads by other users are eventually consistent, at most `max-lag` behind (see
the lag guard). Keep `sticky-window` above `max-lag`, with a margin for clock differences between pods (the window is
measured on the wall clock of two pods); the application logs a warning at start-up when it is not. If the session
cannot be read (Redis down or hung), the request's reads use the primary after that one failed lookup, and a write
whose window cannot be stored still succeeds. During such an outage requests with a session cookie fail in Spring MVC
anyway (it looks the session up for flash attributes on every request), with or without replicas; requests without
one never touch Redis for routing.

Reads do not go backwards within a request: its first replica-eligible read picks a replica and the request keeps
it; if that replica drops out, or a read of the request had to use the primary, the rest of the request reads the
primary, never another replica that could be further behind (the catalog's existence check and the menu load it
guards, for example). A request with a session cookie picks its replica from the cookie, so a session's polls keep
reading one replica while it stays routable. Across requests, reads can still go back by up to `max-lag` when a
session's replica drops out and it moves to another (or that replica returns).

### Lag guard

`ReplicaLagMonitor` checks each replica every `lag-check-interval` on a thread of its own, so a slow or hung replica
delays only its own checks, over its own connections (one to the primary, shared, and one per replica, with the pools'
driver properties and `lag-check-timeout` as connect and socket timeout; outside the pools, so a saturated primary pool
or a hung replica cannot stall it). Each check samples the primary's `pg_current_wal_lsn()`, taking the time just
before, then the replica's `pg_is_in_recovery()` and `pg_last_wal_replay_lsn()`. Every transaction committed before a
sample's time is at or below its position, so a replica that has replayed up to a sample's position has everything
committed before that sample was taken: its data is at most that old. This needs no clock sync between the servers,
and an idle primary's replica is not reported as minutes behind, as `now() - pg_last_xact_replay_timestamp()` would.

Routability is a deadline evaluated on every read, not a verdict kept until the next check: a replica gets reads only
while the last check found it a reachable standby and its data is still less than `max-lag` old now. A replica whose
replay stalls therefore drops out exactly `max-lag` after the last moment it was known to be current, however slow or
stuck the monitor is, and every replica read sees everything committed `max-lag` before it started. The price is
granularity: a replica is only known to be as current as the previous primary sample it has replayed, so under write
load it stays routable while it replays less than `max-lag - 2 x lag-check-interval` behind (1s with the defaults; the
application warns at start-up when `max-lag` is not above twice the interval). A new replica, or one back from an
outage, under write load is routable from its second check. State changes found by a check are logged ("Read replica
replica-1 gets no reads, they go to the primary: LAGGING (data up to 2.400s old, max-lag 2.000s)", "... is routable
again"); a deadline passing between two checks is not.

| Setting (`app.datasource.replica.*`) | Default | Variable |
| --- | --- | --- |
| `urls` | empty (off) | `DATABASE_REPLICA_URLS`, comma-separated (a URL with commas: `APP_DATASOURCE_REPLICA_URLS_0`, `_1`, ...) |
| `username` / `password` | the primary's | `DATABASE_REPLICA_USERNAME` / `DATABASE_REPLICA_PASSWORD` (Secret) |
| `pool.maximum-size` | 20 per replica and pod | `DATABASE_REPLICA_POOL_MAX_SIZE` |
| `pool.connection-timeout` | 1s, then the read runs on the primary | `DATABASE_REPLICA_POOL_CONNECTION_TIMEOUT` |
| `pool.socket-timeout` | 15s (above the 10s statement timeout) | `DATABASE_REPLICA_SOCKET_TIMEOUT` |
| `sticky-window` | 5s; keep it above `max-lag` | `DATABASE_REPLICA_STICKY_WINDOW` |
| `max-lag` | 2s, the most a replica read can lag | `DATABASE_REPLICA_MAX_LAG` |
| `lag-check-interval` | 500ms per replica; keep it below `max-lag / 2` | `DATABASE_REPLICA_LAG_CHECK_INTERVAL` |
| `lag-check-timeout` | 1s | `DATABASE_REPLICA_LAG_CHECK_TIMEOUT` |

Pools: the primary keeps `onlineorder-db` (`DATABASE_POOL_MAX_SIZE`) and its alerts; each replica gets
`onlineorder-db-replica-N` with the primary's Hikari settings (statement and lock timeouts) plus read-only connections
and an `application_name` naming the pool (visible in `pg_stat_activity`). Budget connections per database server:
pods x pool size below its `max_connections` (prod: 6 x 20 on the primary, 6 x 15 on each replica). Catalog caching:
a reader can reload an old row from a replica after an admin write, but only by starting less than `max-lag` after the
commit, so with replicas on the delayed second eviction waits `max-lag` plus `app.cache.delayed-double-delete` (3s with
the defaults; 0 still disables it).

### Failure modes

| Replica state | What happens | Seen as |
| --- | --- | --- |
| Lagging beyond `max-lag` (long query, I/O, replay paused) | Its reads go to the primary from the moment its data is `max-lag` old until a check finds it caught up | lag > 2s, routable 0, `reason="no_replica"` reads |
| Stopped, refusing connections | Out of rotation at its next check (at the latest `max-lag` after its last good one); its pool is evicted then, and again before it takes reads after the outage | lag NaN, routable 0, `UNREACHABLE` in the logs |
| Hung (accepts, never answers) | Out of rotation when its check times out (within `lag-check-interval` plus `lag-check-timeout`), at the latest `max-lag` after its last good check; a read already running on it fails after `socket-timeout`. Other replicas' checks are not delayed | as above |
| Pool exhausted | That read waits `pool.connection-timeout`, then runs on the primary; the replica stays in rotation | `reason="replica_failed"` reads |
| Promoted, or not a standby | Never gets reads (`NOT_STANDBY`): its data diverges from the primary's | routable 0 |
| Primary unreachable | No lag can be measured, every replica leaves rotation (`PRIMARY_UNKNOWN`); reads fail with the primary, as without replicas | routable 0 |
| Query cancelled by recovery | A long replica read can be cancelled when it conflicts with replayed WAL; `hot_standby_feedback=on` (the compose replica sets it) avoids most | 500 on that read |

A read that is in the middle of its transaction on a replica when the replica dies fails like any query on a failing
database; there is no transparent retry. New reads fall back to the primary.

### Metrics and alerts

`onlineorder_db_routing_reads_total{target, reason}` (read-only transactions per pool: `replica-N` with reason
`replica`, or `primary` with `sticky` (read-your-writes window, or the session could not be read), `no_replica` (none
routable, or the request's replica dropped out) and `replica_failed`), `onlineorder_db_replica_lag_seconds{replica}`
(how old the replica's data may be now: an upper bound of its lag, which for a caught-up replica is the time since
its last check, up to about 0.5s), `onlineorder_db_replica_routable{replica}` and
`hikaricp_connections_*{pool="onlineorder-db-replica-N"}`. Alerts:
`OnlineOrderDbReplicaLagging` (lag > 2s for 5m; keep it equal to `max-lag`) and `OnlineOrderDbReplicaUnavailable`
(no reads for 5m: lagging, unreachable or not a standby), with promtool tests in `alerts.test.yml`.

### Running a replica

Locally, `backend/docker-compose.yml` has an optional hot standby behind a profile:

```sh
cd backend
docker compose --profile replica up -d
DATABASE_REPLICA_URLS=jdbc:postgresql://localhost:5434/onlineorder \
  FLYWAY_LOCATIONS=classpath:db/migration,classpath:db/demo ./gradlew bootRun
```

`db` runs with `postgres/pg_hba.conf` (the image's own rules plus password-authenticated replication) and
`db-replica` clones it with `pg_basebackup -R` on an empty volume (`postgres/replica-entrypoint.sh`), then streams. No
replication slot is created, so a replica left stopped for long while the primary writes may fall too far behind to
resume: delete the `onlineorder-pg-replica-local` volume and start it again. Managed PostgreSQL offers read replicas
of its own; point `DATABASE_REPLICA_URLS` at their endpoints, one URL per physical replica. Do not use a
load-balanced reader endpoint: the monitor's single connection measures whichever server it landed on, while the pool's
connections spread over all of them, so a lagging server behind it would keep serving reads with no lag guard and
`max-lag` would not hold. On Kubernetes the overlays set
`DATABASE_REPLICA_URLS` in the ConfigMap; a separate replica login goes in the Secret.

Tests: `ReadReplicaRoutingIntegrationTests` (a real primary and streaming replica on the official image, built by
`PostgresReplicationContainers` from the same two files) proves which transactions read the replica, read-your-writes,
the lagging, stopped and hung cases, and that a replica whose replay stops right after a write gets no read once the
write is `max-lag` old, next to a hung second replica; `ReadReplicaSessionStoreOutageTests` keeps catalog reads on the
replica with Redis hung; `FulfilmentReplicaRoutingContainerTests` runs the whole order flow with routing on;
`ReplicaTargetTests` (the deadline on a virtual clock, with check rounds up to 5s), `ReplicaRouterTests`,
`ReplicaLagMonitorTests`, `WalPositionHistoryTests`, `ReadYourWritesTrackerTests` and
`ReadWriteRoutingConfigurationTests` cover the parts.

## 12. After-sales refunds (V12-V12_4)

Refund requests on completed orders and the automatic refund of cancelled paid orders. Cases, refunds, the poller and
the customer and admin API are in `backend/src/main/java/com/laioffer/onlineorder/aftersales/`, the merchant's side in
`fulfilment.merchant`, the customer notifications in `fulfilment.notification`. The states, deadlines and amount rules
are in [docs/adr/0002-after-sales-state-machine.md](adr/0002-after-sales-state-machine.md), and what was built in
[docs/adr/0002-after-sales-implementation.md](adr/0002-after-sales-implementation.md); this section is the operator's
side.

### How a refund is paid

- A case reaches `REFUNDING` with an approved amount when the merchant approves, a counter-offer is accepted or times
  out, the merchant or the platform does not answer in time, or an admin arbitrates. A paid order cancelled from `PAID`
  or `ACCEPTED` opens an `ORDER_CANCELLED` case of its whole total, straight in `REFUNDING`, in the cancel's own
  transaction. The step that enters `REFUNDING` inserts the case's `refunds` row: `PENDING`, due at once, with
  `gateway_key` `after-sales-case:{caseId}`.
- The after-sales poller (`AfterSalesPoller`, thread `after-sales-poller`, not the shared scheduler) runs on every
  instance; its claims use `FOR UPDATE SKIP LOCKED`, so two instances never take the same case or refund. Each tick
  fires the due deadlines, then executes the due refunds one at a time:
  1. The claim transaction takes an expired lease first, else the most overdue `PENDING` refund, counts an attempt and
     leases it (`PROCESSING`) for `refund.claim-timeout`. Before the lease it checks the refund against its case and
     the order's `order_payments` row: the case is `REFUNDING`, the refund is its approved amount, and the payment it
     names captured at least that much. A refund that fails the check is given up here, without a call.
  2. The gateway call runs outside any transaction, on one of `refund-gateway.max-threads` threads (`refund-gateway-N`),
     and is waited for at most `refund-gateway.timeout`.
  3. The record transaction marks the refund `SUCCEEDED` and its case `REFUNDED` (event `aftersales.case_refunded`).
     A retryable failure goes back to `PENDING` for `retry-backoff` x 2^(attempts - 1), at most `retry-backoff-max`; a
     permanent failure, or the failure of the last of `max-attempts`, makes it `FAILED` with `last_error`. The case
     stays `REFUNDING`; no event or notification is sent for a `FAILED` refund.
- With the defaults a gateway that keeps failing gives a refund up about 21 minutes after its first attempt: 8 attempts,
  10s, 20s, 40s, 80s, 160s, 320s and 600s apart.
- The gateway must be idempotent per `gateway_key`: a call that outlived its timeout may still arrive, and a refund
  whose executor died is reclaimed after its lease and called again with the same key. `SimulatedRefundGateway`, the
  implementation in this repository, is idempotent: it answers `SIMREF-` and a hash of the key. It fails the first
  `AFTER_SALES_REFUND_GATEWAY_FAIL_FIRST_ATTEMPTS` (0) attempts of every refund as retryable, for demos and tests. A
  real gateway has to deduplicate on the key too.

| Setting (`app.after-sales.*`) | Default | Variable |
| --- | --- | --- |
| `poller.enabled` | follows `app.kafka.enabled`: on, off in `h2`, on in `h2,demo` | `AFTER_SALES_POLLER_ENABLED` |
| `poller.interval` | 1s between ticks | `AFTER_SALES_POLL_INTERVAL` |
| `poller.batch-size` | at most 100 cases, then 100 refunds per tick | `AFTER_SALES_POLL_BATCH_SIZE` |
| `poller.tick-budget` | 5s per tick, for the deadlines and then the refunds | `AFTER_SALES_POLL_TICK_BUDGET` |
| `poller.retry-backoff` | 30s before a deadline that failed to fire is tried again | `AFTER_SALES_DEADLINE_RETRY_BACKOFF` |
| `refund.max-attempts` | 8 gateway calls, a reclaim after an expired lease included | `AFTER_SALES_REFUND_MAX_ATTEMPTS` |
| `refund.retry-backoff` | 10s, doubled after every further failed call; a failed claim waits 10s, never doubled | `AFTER_SALES_REFUND_RETRY_BACKOFF` |
| `refund.retry-backoff-max` | 10m | `AFTER_SALES_REFUND_RETRY_BACKOFF_MAX` |
| `refund.claim-timeout` | 60s lease of a claimed refund | `AFTER_SALES_REFUND_CLAIM_TIMEOUT` |
| `refund.max-claim-failures` | 10 claims failed in a row (`backed_off`) before the last one gives the refund up, no attempt used up; at least 1 | `AFTER_SALES_REFUND_MAX_CLAIM_FAILURES` |
| `refund-gateway.timeout` | 10s; must be below `refund.claim-timeout` | `AFTER_SALES_REFUND_GATEWAY_TIMEOUT` |
| `refund-gateway.max-threads` | 4 gateway calls at once, calls that outlived their timeout included | `AFTER_SALES_REFUND_GATEWAY_MAX_THREADS` |
| `refund-gateway.fail-first-attempts` | 0: `SimulatedRefundGateway` fails the first N attempts of every refund (retryable); demos and tests only, never negative | `AFTER_SALES_REFUND_GATEWAY_FAIL_FIRST_ATTEMPTS` |
| `request-window`, `merchant-response-timeout`, `customer-response-timeout`, `arbitration-timeout` | 24h, 24h, 48h, 72h | `AFTER_SALES_REQUEST_WINDOW`, `AFTER_SALES_MERCHANT_RESPONSE_TIMEOUT`, `AFTER_SALES_CUSTOMER_RESPONSE_TIMEOUT`, `AFTER_SALES_ARBITRATION_TIMEOUT` |
| `picked-up-cancel-refund-percent` | 50: the share of the total a customer's cancel after the pickup refunds (0-100, half-even; 0 refunds nothing); an admin's cancel always refunds in full | `AFTER_SALES_PICKED_UP_CANCEL_REFUND_PERCENT` |
| `topic`, `merchant-group`, `notification-group` | `after-sales-events`, `onlineorder-merchant-aftersales`, `onlineorder-aftersales-notification` | `KAFKA_AFTER_SALES_TOPIC`, `AFTER_SALES_MERCHANT_GROUP`, `AFTER_SALES_NOTIFICATION_GROUP` |

The application does not start when `refund.max-attempts`, `refund.max-claim-failures` or `refund-gateway.max-threads`
is below 1, `refund-gateway.fail-first-attempts` is negative, a time limit, backoff, timeout or the tick budget is not
positive, `retry-backoff-max` is below `retry-backoff`, or `refund-gateway.timeout` is not below
`refund.claim-timeout`. `poller.interval` and `poller.batch-size` are not checked: an interval below 1 ms runs as 1 ms,
a batch size below 1 as 1. The tick budget bounds what a slow or hung gateway costs the deadlines: each job stops
claiming once the budget is spent, after one case or refund at least, and a call already started runs to its timeout,
so the next tick's deadlines wait about the budget plus one gateway timeout (15s with the defaults).

**Demo profile.** `SPRING_PROFILES_ACTIVE=h2,demo` (`application-demo.yml`) sets a 2h request window, 2m for the
merchant, 3m for the customer and 5m for the platform, and turns the poller on although `h2` has no Kafka; the variables
above still override it. The steps are in docs/after-sales.md "After-sales demo". With `h2` nothing consumes `after-sales-events`, so
the merchant inbox stays empty (merchant commands answer 404) and no notifications are written; the merchant timeout
still fires from the database and approves the request. `demo` on the full local stack shortens the same limits with
Kafka on. The Kubernetes overlays do not set it.

### Metrics and alerts

| Metric (Prometheus name) | Meaning |
| --- | --- |
| `onlineorder_aftersales_refunds_total{outcome}` | Refunds the executor handled, counted after their transaction committed: `succeeded`, `retried` (waits for its backoff), `failed` (given up), `superseded` (another instance reclaimed it after the lease ended; this result was discarded), `unrecorded` (the result could not be recorded, or the wait was interrupted at shutdown; reclaimed after the lease), `skipped` (claimed but not due after all; ends the run), `backed_off` (the claim transaction failed) |
| `onlineorder_aftersales_cancellations_undercaptured_total` | Paid orders cancelled with a refund (the total, or a share after the pickup) above their captured payment |
| `onlineorder_aftersales_refund_gateway_threads_busy`, `..._threads_max` | Refund gateway threads running or reserved for a call (calls that outlived their timeout included), and `refund-gateway.max-threads` |
| `onlineorder_aftersales_refund_gateway_saturated_total` | Executor runs that claimed nothing because every gateway thread was held; registered at 0 on startup |
| `onlineorder_aftersales_deadlines_total{trigger,outcome}` | Deadlines the poller handled: `trigger` `merchant_timeout`, `offer_timeout`, `customer_timeout`, `arbitration_timeout` (`none` for a failure on a case whose status has none); `outcome` `fired`, `skipped` (refused, nothing written) or `failed` (rolled back, backs off) |
| `onlineorder_aftersales_steps_total{action,from,to}` | Committed steps of cases: `action` `open`, `order_cancelled` or the trigger (`merchant_approve`, `customer_withdraw`, `admin_approve_partial`, `refund_succeeded`, ...), `from` `none` for an opening, statuses in lower case |
| `onlineorder_aftersales_merchant_decisions_total{decision}` | Merchant answers that changed a case: `approved`, `rejected`, `offered` |

Every series is registered by its first increment, so it starts at 1; `OnlineOrderRefundsGivenUp` and
`OnlineOrderCancellationsUndercaptured` therefore also fire on a series that appeared within their 15-minute window,
as `OnlineOrderDeadLettersGrowing` does. `OnlineOrderRefundsBackingOff` and `OnlineOrderAfterSalesDeadlinesFailing`
need back-offs (failed claims, failed deadlines) in every 5-minute window for 15 minutes, so a database blip does not
raise them. The alerts mark when something happened; a `FAILED` refund stays
`FAILED` after the alert resolved. Tests are in `alerts.test.yml`.

### Runbook

**A refund was given up** (`OnlineOrderRefundsGivenUp`). List the refunds (the admin console's After-sales section shows
the same table with a retry button):

```sh
curl -sS -f "$BASE_URL/admin/refunds?status=FAILED&limit=100" -b "$ADMIN_COOKIE"
```

`status` is `PENDING`, `PROCESSING`, `SUCCEEDED` or `FAILED` in any letter case (`FAILED` when left out, 400 for an
unknown status), `limit` 1-100 (50), newest first. Each entry has `case_id`, `order_id`, `order_no`, `attempts` and
`last_error`; the ERROR log line "Refund ... gave up after N attempt(s): ..." has the same error. With read replicas
the list may be up to `max-lag` behind (section 11).

| `last_error` | Cause | What to do |
| --- | --- | --- |
| the gateway's error, for example "The refund gateway did not answer within 10000 ms" | a permanent failure, or the last of `max-attempts` retryable ones | fix the gateway side, then retry |
| "The lease of attempt N expired without a result and no attempt is left" | the instance making the last attempt died or stalled; that call may have refunded | retry: the gateway answers the same key with the same refund |
| "Case ... is ..., not REFUNDING", "The refund of ... is not the approved amount ...", "Order ... has no captured payment", "The refund names the payment ... but order ... was paid by ...", "The refund of ... exceeds the ... captured by payment ..." | the refund does not match its case or the order's payment; checked before every call, so nothing was paid | a retry fails the same way: correct the data, or refund the customer by hand |
| "10 claims failed in a row; the last one: The claim of attempt N failed: ..." | its claim transaction failed `max-claim-failures` times in a row before any call, so nothing was paid (see "Refunds that keep backing off" below) | fix that cause, then retry |

Then retry it:

```sh
curl -sS -f -X POST "$BASE_URL/admin/refunds/<id>/retry" -b "$ADMIN_COOKIE"
```

The refund becomes `PENDING`, due at once, with its attempts reset, and the next tick calls the gateway with the same
`gateway_key`; `last_error` stays until that attempt. The audit log has `refund.retry` with `case_id`, `order_id`,
`previous_attempts` and `last_error`. A refund that is already `PENDING` answers 200 unchanged and is not audited again;
`PROCESSING` or `SUCCEEDED` answers 409 `REFUND_STATUS_CONFLICT`, an unknown id 404. The retry takes no
`Idempotency-Key`.

**Many refunds given up at once** (a gateway outage longer than the 8 attempts, about 21 minutes with the defaults).
Fix the gateway first. The retries share the admin's `refund-retry` bucket in `RateLimitingFilter`: 10 tokens per
login, one more every 6 s, the console's retry button included. A loop over more than 10 refunds therefore paces itself
at one retry every 6 s after the first 10 (100 refunds take about 9 minutes) or retries a `429 RATE_LIMITED`, which
retried nothing. The loop below does the latter: `--retry 5 --retry-delay 6` waits one refill and sends the request
again on a 429 (also on a 408, 500, 502, 503 or 504, which is safe: a refund already `PENDING` answers unchanged), and
`-sS -f` prints every other failure, such as a 409 for a refund another admin retried meanwhile, and goes on with the
next one. curl also prints each 429 it retries; only the refunds the loop names were not retried.

```sh
curl -sS -f "$BASE_URL/admin/refunds?status=FAILED&limit=100" -b "$ADMIN_COOKIE" | jq -r '.[].id' > failed-refunds.txt
while read -r id; do
  curl -sS -f --retry 5 --retry-delay 6 -o /dev/null -X POST "$BASE_URL/admin/refunds/$id/retry" -b "$ADMIN_COOKIE" \
    || echo "refund $id was not retried" >&2
done < failed-refunds.txt
curl -sS -f "$BASE_URL/admin/refunds?status=FAILED&limit=100" -b "$ADMIN_COOKIE" | jq length
```

The last command lists `status=FAILED` again and should print 0. With more than 100 given up, run the loop again for
the next ones (the retried refunds have left `FAILED`). A refund that is listed again failed once more: read its
`last_error` rather than retrying it again. A mismatch from the table above fails at its next claim, within seconds; a
gateway that still fails gives the refunds up again after about 21 minutes, so list `FAILED` once more after that.

**Refunds that keep backing off** (`OnlineOrderRefundsBackingOff`, `outcome="backed_off"`). The claim transaction failed
after it had claimed the refund, before any call: a database error (lock or statement timeout, a lost connection) or an
exception while the refund was checked against its case and payment. Only that refund backs off, in a transaction of
its own and only while it is still at the version claimed: its next attempt, or for an expired lease the lease's end,
moves `retry-backoff` (10s) ahead and `last_error` becomes "The claim of attempt N failed: ...". The refunds behind it
are still executed. It uses up no attempt, but it counts in `claim_failures` (V12_3): once
`app.after-sales.refund.max-claim-failures` (10, about 100 s of failures at the default backoff) of its claims failed in
a row, the last one gives it up (`FAILED`, attempts unchanged, `last_error` "10 claims failed in a row; the last one:
The claim of attempt N failed: ...", `OnlineOrderRefundsGivenUp` follows) for an admin to retry once the cause is fixed;
any other step of the refund, an admin retry included, starts the count over. Until then an admin retry of a `PENDING`
refund changes nothing. Find it in `GET /admin/refunds?status=PENDING` (or `PROCESSING`) by its `last_error`, or:

```sql
SELECT id, case_id, order_id, status, attempts, claim_failures, next_attempt_at, claimed_until, last_error
FROM refunds
WHERE status IN ('PENDING', 'PROCESSING') AND last_error LIKE 'The claim of attempt %'
ORDER BY updated_at;
```

The WARN log "The claim of refund <id> of case <case> (order <order>) failed before attempt N; retrying at ..." carries
the exception. Fix the cause; the next claim then proceeds as usual. A failure of the claim query itself (database
unreachable) is not counted: the run ends, and the poller logs "After-sales refund poll failed; retrying every ..."
with the stack trace once, then "After-sales refund poll failed N times in a row: ..." per failed tick, and "After-sales
refund poll resumed after N failed poll(s)" once it works again.

**A hung gateway.** A call that does not answer within `refund-gateway.timeout` counts as a retryable failure and is
interrupted; one that ignores the interrupt keeps its thread. Once all `max-threads` threads are held that way, no
refund is claimed, so none uses up an attempt, until one of them returns: WARN "All 4 refund gateway threads are held
by calls that did not return within their timeout; ...", then INFO "A refund gateway thread is free again; ...".
Meanwhile `onlineorder_aftersales_refund_gateway_threads_busy` equals `onlineorder_aftersales_refund_gateway_threads_max`,
`onlineorder_aftersales_refund_gateway_saturated_total` grows by one per poller run (about one a second), and after 10
minutes `OnlineOrderRefundGatewaySaturated` fires. `PENDING` refunds whose `next_attempt_at` has passed pile up while
`onlineorder_aftersales_refunds_total` stands still. Check the payment gateway; a restart frees the threads, and the
refunds whose calls were cut off are reclaimed once their leases end, with the same gateway key.

**An undercaptured cancellation** (`OnlineOrderCancellationsUndercaptured`). A paid order was cancelled with a total
above what its `order_payments` row captured. Checkout records the order's total as captured, so this points at a row
changed outside the application. The `ORDER_CANCELLED` case is opened anyway, and the WARN "Order <id> was cancelled
with a refund of ... but its payment ... captured only ...; the refund of case <case> will be given up ..." names the
order, the payment and the case. The refund becomes `FAILED` at its first claim with "The refund of ... exceeds the ...
captured by payment ..." (`OnlineOrderRefundsGivenUp` fires too), and a retry fails the same check. Refund the customer
by hand, outside the application; the case stays `REFUNDING` and its refund `FAILED`, since no endpoint closes either.

**Paid orders without a payment record.** Checkout writes `order_payments` (`CAPTURED`, provider reference `SIMPAY-...`)
in the transaction that creates a `PAID` order. Orders paid before V12 have no row, nor do orders checked out as
`PLACED` and moved to `PAID` by an admin, nor orders that a pod of the previous release took during the rollout or
after a rollback. Such an order gets no automatic refund when it is cancelled (WARN "Order <id> was cancelled after
payment without a captured payment; no automatic refund"; no case, no metric), and it cannot be submitted for
after-sales: it is left out of `eligible_orders`, and `POST /orders/{orderId}/after-sales` answers 409
`AFTER_SALES_NOT_ELIGIBLE` "Order has no captured payment". Refund those customers by hand. The paid orders cancelled
without a refund since the release:

```sql
SELECT o.id, o.order_no, o.total_price, o.currency, h.occurred_at AS cancelled_at
FROM orders o
JOIN order_status_history h ON h.order_id = o.id
WHERE o.status = 'CANCELLED' AND o.total_price > 0
  AND h.to_status = 'CANCELLED' AND h.from_status IN ('PAID', 'ACCEPTED')
  AND h.occurred_at >= '<V12 rollout, UTC>'
  AND NOT EXISTS (SELECT 1 FROM order_payments p WHERE p.order_id = o.id);
```

**Deadlines.** A deadline whose step rolled back (`onlineorder_aftersales_deadlines_total{outcome="failed"}`, WARN
"After-sales deadline <trigger> of case <id> (order <order>) failed (attempt N); retrying at ...") is tried again after
`poller.retry-backoff` (30s), with no limit on the tries. Its `deadline_at` does not move, so the customer's and the
merchant's answers stay refused meanwhile. `OnlineOrderAfterSalesDeadlinesFailing` fires once deadlines have kept
failing for 15 minutes; a poller that is off or stopped counts nothing, so it raises no alert. A case whose deadline
passed minutes ago means the poller is off, stopped or keeps failing (the application writes UTC, section 3):

```sql
SELECT id, order_id, status, deadline_at, deadline_retry_at, deadline_attempts
FROM after_sales_cases
WHERE deadline_at < (now() AT TIME ZONE 'UTC') - INTERVAL '5 minutes'
ORDER BY deadline_at;
```

**Arbitration queue.** `GET /admin/after-sales` lists `IN_ARBITRATION` cases by default (another status with
`?status=`, any letter case; 400 for an unknown status), the nearest deadline first. A case nobody arbitrates within
`arbitration-timeout` (72h) is refunded the amount requested. `POST /admin/after-sales/{caseId}/arbitrate` takes an
`Idempotency-Key` and is audited as `after_sales_case.arbitrate`.

**Events.** After-sales events go through the outbox to `after-sales-events` (aggregate type `AFTER_SALES_CASE`), so a
`FAILED` after-sales event holds back its case's later events like any other (`OnlineOrderOutboxAggregatesBlocked`,
section 8): that case's merchant inbox row and notifications stop moving, while the case itself still moves in the
database. The two consumer groups use `orderEventKafkaListenerContainerFactory`; a record they cannot handle goes to the
order DLT and is replayed with `POST /dead-letters/{id}/replay`. The merchant inbox only moves forward by the case's
version; a replayed older event counts in
`onlineorder_events_stale_skipped_total{consumer="merchant-aftersales-consumer"}`. Merchant commands need the inbox
row: until `onlineorder-merchant-aftersales` has consumed the case's `aftersales.case_opened`, the merchant's commands
on it answer 404 (`OnlineOrderKafkaConsumerLag` covers the group), while the merchant's deadline runs from the opening.

**Rate limits.** Every after-sales `POST` has a per-login bucket in `RateLimitingFilter`: `customer-after-sales` (the
customer's five commands, 20 and one every 3 s), `merchant-after-sales` (approve, reject, offer; 30 and one every 2 s),
`after-sales-arbitrate` and `refund-retry` (10 and one every 6 s each). Refusals count in
`onlineorder_security_rate_limit_rejected_total{route="..."}`. Replays of an `Idempotency-Key` take a token like any
request; a refused one never reached the handler and can be retried with its key.

Not covered yet: the after-sales tables (cases, lines, history, refunds, payments) have no retention or partitioning; a
customer is not notified when a refund is given up.

### Rollout (V12-V12_4)

**V12** creates `order_payments`, `after_sales_cases`, `after_sales_case_lines`, `after_sales_case_history`, `refunds`
and `merchant_after_sales_cases`, all empty, and changes one existing table: `order_notifications` gets
`dedup_scope TEXT NOT NULL DEFAULT ''` (catalog only) and its unique key `uq_notification_order_event (order_id,
event_type)` is replaced by `uq_notification_order_event_scope (order_id, event_type, dedup_scope)`. Adding that
constraint builds a unique index over the whole table under the `ACCESS EXCLUSIVE` lock the `ADD COLUMN` took: reads and
writes of `order_notifications` wait. The foreign keys of the new tables take `SHARE ROW EXCLUSIVE` on `orders` and
`order_history_items` at the start of V12, and Flyway holds every lock until V12 commits, so inserts and updates of
orders (checkouts, every status change) wait for the index build as well. On a large `order_notifications` (several rows
per order) V12 is therefore not online-safe. Measured on PostgreSQL 15 in Docker on a laptop, table in cache: 2 million
rows in 0.4 s; a colder or larger table takes longer. V12 cannot be prepared ahead like V7: it adds the column and the
constraint without `IF NOT EXISTS` and always builds its own index.

**V12_1** adds the currency checks of the six new tables (empty, nothing scanned). **V12_2** adds the nullable
`idempotency_requests.case_id` (catalog only) and its foreign key `fk_idempotency_after_sales_case` to
`after_sales_cases (id) ON DELETE CASCADE`. The key is validated at once (it is not added `NOT VALID`): one scan of
`idempotency_requests`, which finds every `case_id` NULL and so looks nothing up, under the `ACCESS EXCLUSIVE` lock of
the column change until V12_2 commits. Checkouts and every idempotent command write that table and wait. It only keeps
`CLEANUP_IDEMPOTENCY_RETENTION` (3d) of requests; 500,000 rows took 26 ms in the same setup. **V12_3** adds
`refunds.claim_failures INTEGER DEFAULT 0 NOT NULL` (catalog only: a constant default) and its check
`chk_refund_claim_failures`, validated by one scan of `refunds` under the `ACCESS EXCLUSIVE` lock of the column change;
only the refund executor and the admin refund endpoints wait, and `refunds` holds one row per refunded case. **V12_4**
adds `orders.picked_up_at TIMESTAMP` (nullable, no default: catalog only, a brief `ACCESS EXCLUSIVE` lock on `orders`
and no rewrite). The order-delivery consumer fills it from `delivery.picked_up` from then on; deliveries picked up
before the release have none, so a customer who cancels one of those still gets the whole order back. An older pod
neither writes nor reads the column.

How to run it: time V12 against a restored copy of production first (`SELECT COUNT(*),
pg_size_pretty(pg_total_relation_size('order_notifications')) FROM order_notifications`), then follow steps 1-4 of
"Deploying the release with V7-V7_2" (section 7): `SPRING_FLYWAY_INITSQLS` with a `lock_timeout`, scale to zero, one
replica that migrates alone, then the autoscaler's `minReplicas`. Then no application traffic waits on these locks, and
only the migrations' duration matters: it must fit the `startupProbe` allowance (about three minutes); raise its
`failureThreshold` for this release, or plan a maintenance window, when it does not. Create `after-sales-events`
beforehand where the application may not create topics (it uses `KAFKA_ORDER_TOPIC_PARTITIONS` partitions and
`KAFKA_ORDER_TOPIC_REPLICAS` replicas); until it exists, after-sales events fail to publish (section 8).

No pod of the previous release may run next to this one:

- The previous release inserts notifications with `ON CONFLICT (order_id, event_type)`. Once V12 has dropped that key,
  PostgreSQL rejects the statement ("there is no unique or exclusion constraint matching the ON CONFLICT
  specification"), so the old pods' consumers fail, and dead-letter, every event they write a notification for: every
  order event, and `delivery.assigned`, `delivery.picked_up` and `delivery.delivered`. Replay those dead letters once
  only pods of this release run.
- The previous release writes no `order_payments` row at checkout and opens no refund when it cancels a paid order: the
  orders it takes or cancels get no automatic refund (see "Paid orders without a payment record").

A rollback after V12 has the same two effects for as long as the previous release runs: every event that writes an
order or delivery notification is dead-lettered (replay them after rolling forward), and paid orders it cancels get no
refund case. Open cases stop meanwhile, since only this release fires their deadlines and executes their refunds; they
continue once it is back. Giving the previous release its key back (`UNIQUE (order_id, event_type)`) only works while
no order has two notifications of the same event type, which an order's second after-sales request produces. Prefer
rolling forward.

### Connections: listener threads and the pool

With Kafka on, a pod runs 10 listener containers: the order consumer and the dead-letter listener
(`KAFKA_LISTENER_CONCURRENCY`, 3), and the six fulfilment and two after-sales listeners
(`FULFILMENT_LISTENER_CONCURRENCY`, which defaults to the same 3). That is 30 consumer threads per pod, up from 24
before this release. A consumer thread holds a connection of the primary pool (`onlineorder-db`) while it handles a
record. The same pool serves the shared scheduler (`TASK_SCHEDULING_POOL_SIZE`, 4 threads: outbox publisher, cleanup,
partition maintenance), the fulfilment deadline poller and the after-sales poller (one transaction at a time each; the
refund gateway call holds no connection) and every HTTP request.

| | `DATABASE_POOL_MAX_SIZE` per pod | Consumer threads per pod | Pods |
| --- | --- | --- | --- |
| local (`application.yml`) | 30 | 30 | 1 |
| base | 30 | 30 | 2-3 (HPA) |
| staging | 30 (from base) | 30 | 1-2 |
| prod | 20 | 30 | 3-6 |

Kafka limits how many of them have work: every topic has `KAFKA_ORDER_TOPIC_PARTITIONS` (3) partitions, and a group's
partitions are spread over its consumers on all pods, so the whole cluster has at most 30 consumer threads with
partitions (10 x 3), however many pods run: 5-10 per pod when prod's 3-6 pods share them evenly. The case to watch is
a pod that holds most partitions (the only pod up, the others restarting, or an uneven assignment) while its topics
have a backlog, for example after a Kafka outage or a consumer restart. If its consumers took the connections,
requests would wait up to `DATABASE_POOL_CONNECTION_TIMEOUT_MS` (2000) and answer 503, and a consumer that timed out
would retry the record as a transient failure for up to `KAFKA_LISTENER_TRANSIENT_RETRY_TIMEOUT` (5m) before
dead-lettering it; `OnlineOrderHikariPendingSustained` and `OnlineOrderHikariConnectionTimeouts` would show it.

Measured on 2026-10-21 with `load-tests/order-flow.js` (build of 69ab24f; the later after-sales commits add one
statement per notified after-sales event). One backend with Kafka on, which is the pod holding every partition:
1 GB heap on a 15-core machine shared with k6 and the Compose services, no read replica (the primary pool served
every read), `SECURITY_RATE_LIMIT_ENABLED=false` and `FULFILMENT_ACCEPT_TIMEOUT=30s`, so the merchant timeout
cancelled and refunded every paid order and the order, merchant and after-sales topics all carried records: 7 of the
10 listeners had work (21 threads at concurrency 3, 9 at 1). The script never accepts an order, so the two delivery
listeners and the DLT listener stayed idle. Each run: 90 s at a fixed number of VUs, fresh users, the actuator
scraped every second; "inbox p95" is the time from the payment to the merchant inbox row (`merchant_orders`), which
shows whether the consumers keep up. The 100-VU runs (about 285 req/s) left the pool idle in all three
configurations and are not listed.

| VUs | Pool / concurrency | req/s | p95 / p99 ms | Errors | Pending avg / max | Acquire p99 | Timeouts | Inbox p95 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 300 | 30 / 3 | 820 | 82 / 270 | 0 | 0.9 / 13 | 11 ms | 0 | 0.6 s |
| 300 | 20 / 3 | 835 | 72 / 113 | 0 | 2.5 / 26 | 15 ms | 0 | 0.6 s |
| 300 | 20 / 1 | 838 | 70 / 95 | 0 | 0.5 / 21 | 1 ms | 0 | 0.7 s |
| 500 | 30 / 3 | 1123 | 544 / 900 | 0 | 27 / 90 | 179 ms | 0 | 1.9 s |
| 500 | 20 / 3 | 1120 | 556 / 876 | 0 | 47 / 146 | 268 ms | 0 | 2.2 s |
| 500 | 20 / 1 | 1156 | 505 / 833 | 0 | 32 / 115 | 224 ms | 0 | 44 s |
| 700 | 30 / 3 | 1168 | 920 / 1368 | 0 | 60 / 166 | 358 ms | 0 | 24 s |
| 700 | 20 / 3 | 1168 | 927 / 1416 | 0 | 82 / 185 | 447 ms | 0 | 28 s |
| 700 | 20 / 1 | 1207 | 851 / 1389 | 0 | 59 / 127 | 358 ms | 0 | 61 s |

With a backlog, 300 VUs, each run on a freshly started backend. "Real": the order events of 31,000 orders that no
consumer had seen (about 125,000 records over the four order-topic groups, full processing; the merchant events,
cancellations and refunds they caused followed during the run), drained in the first 60 s. "Replay": fresh consumer
group ids (`auto-offset-reset: earliest`), so every listener re-read its topic's history, about 1.8 million records,
each a `processed_events` dedup hit (one short transaction); it was still running when the run ended, since none of
the orders placed meanwhile reached the merchant inbox.

| Backlog | Pool / concurrency | req/s | p95 / p99 ms | Pending avg / max | Active avg | Acquire p99 | Timeouts |
| --- | --- | --- | --- | --- | --- | --- | --- |
| none | 20 / 3 | 816 | 87 / 235 | 2.2 / 49 | 8.6 | 34 ms | 0 |
| real | 20 / 3 | 792 | 116 / 483 | 6.8 / 84 | 12.6 | 67 ms (90 ms while it drained) | 0 |
| replay | 30 / 3 | 802 | 93 / 213 | 3.2 / 25 | 26.1 | 6 ms | 0 |
| replay | 20 / 3 | 809 | 94 / 219 | 8.5 / 60 | 19.3 | 22 ms | 0 |
| replay | 20 / 1 | 826 | 77 / 158 | 0.4 / 10 | 12.9 | 1 ms | 0 |

What it shows:

- No run had a Hikari timeout, a failed request, a transient listener retry or a dead letter, prod's 20 connections
  included. The worst acquire p99, 447 ms in the CPU-bound 700-VU run, stays far below the 2000 ms timeout.
- A pool of 20 instead of 30 changes where requests queue, not what they get: throughput, p95 and p99 stay within a
  few percent at every load. From 500 VUs the machine's CPU (97% busy) is the limit, and the smaller pool only holds
  more of the waiting requests.
- Busy consumers do not lock requests out. A consumer thread borrows a connection for one record's transaction and
  returns it before it takes the next record (a connection was held 2-10 ms on average across all borrowers here),
  and HikariCP hands a returned connection to the threads already waiting. Under the replay, the 20 connections were
  in use 97% of the time (19.3 active on average, a thread waiting in 91% of the samples), yet connections were
  handed out within 22 ms at p99. The real backlog, heavier per record, cost more (p99 483 ms against 235 ms without a
  backlog) and stayed far from the timeout as well.
- `FULFILMENT_LISTENER_CONCURRENCY=1` relieves the pool, but on a pod that holds every partition, the one case where
  the pool is at risk, one thread per listener does not keep up: at 500 VUs (165 checkouts/s) merchants saw paid
  orders 44 s late at p95, against 2.2 s with 3 threads.

Recommendation: keep prod at `DATABASE_POOL_MAX_SIZE=20` and `FULFILMENT_LISTENER_CONCURRENCY` at its default of 3,
with no change under `deploy/`. Lowering the concurrency would trade a pool risk that did not show for consumer lag
that did. Raising the pool does not help either, since requests got the same latency from 30 connections. If
`OnlineOrderHikariConnectionTimeouts` fires, check the pod's CPU throttling first (it lengthens every transaction)
and only then the pool, keeping `maxReplicas x DATABASE_POOL_MAX_SIZE` below `max_connections` (prod: 6 x 20 = 120
today). The limits of this measurement: a prod pod has 2 CPUs (limit) and read replicas for the read-only endpoints,
where this backend had 15 cores to share and served every read from the primary. A throttled pod holds each
connection longer, so its acquire times can be higher than these at a far lower request rate. The delivery and DLT
listeners (9 threads at concurrency 3) had no records. Watch acquire p99 and pending on the dashboard during the first
rollout to staging, which has the real pod limits and a replica.


## 13. Push signals (SSE)

`GET /events/stream` ([ADR 0003](adr/0003-push-signals.md), docs/push-signals.md "Push Signals (SSE)") keeps one text/event-stream
response open per signed-in browser tab. After a commit that changes a merchant's orders or after-sales cases, a
rider's deliveries or a customer's notifications, the writer publishes a signal without data, and the open streams of
that restaurant, rider or customer tell their page to re-read its REST list. The consoles keep polling every 60
seconds while their stream is open (15 s, or 10 s for refund cases, without it), so everything here degrades to
polling, never to wrong data.

### What runs where

| Thread | Does |
| --- | --- |
| `push-keepalive` | Every `PUSH_KEEPALIVE_INTERVAL` (25s): `:keepalive` to every stream (finds the clients that went away), then the active flag of every merchant and rider stream, read from the primary; a deactivated login gets `event: forbidden` and its stream ends |
| `push-publisher` | The Redis `PUBLISH` of each committed signal (`PUSH_FANOUT=redis`), so a writer never waits for Redis |
| `push-delivery` | Writes each signal the subscription received to this pod's streams of its recipient |
| `push-subscriber` | Starts the subscription in the background and retries it (1s, doubling to 30s) while Redis is unreachable at startup; afterwards Lettuce reconnects and subscribes again by itself. Once the subscription started, every `PUSH_PROBE_INTERVAL` (30s) it publishes a probe on the `:probe` channel and checks that the previous one came back, which feeds `onlineorder_push_subscribed` |

None of them is a `TaskScheduler` bean: a second one would make Spring Boot back off the scheduler every `@Scheduled`
job runs on. Their queues hold 10,000 signals each; a full queue drops the signal and counts it as a publish failure.

### Redis channel

Every pod publishes on and subscribes to `onlineorder:push:v1` (`PUSH_CHANNEL`), messages like
`{"recipient":"restaurant:5","topic":"merchant.orders"}` (recipients `customer:{id}`, `restaurant:{id}`, `rider:{id}`;
topics `notifications`, `merchant.orders`, `merchant.after_sales`, `rider.deliveries`). Pub/sub keeps nothing: a pod
that is not subscribed when a message is published never sees it. To know that it still receives, every pod also
subscribes to the sibling channel `onlineorder:push:v1:probe` (the signal channel plus `:probe`) and publishes there,
every `PUSH_PROBE_INTERVAL` (30s), a probe `{"probe":"<instance id>","seq":N}` (the instance id is a random UUID
made at startup, the sequence a counter); every pod receives every other pod's probes and ignores them, and only its
own coming back before the next one keeps `onlineorder_push_subscribed` at 1. Check it with:

```sh
redis-cli PUBSUB NUMSUB onlineorder:push:v1    # one subscriber per running pod
redis-cli SUBSCRIBE onlineorder:push:v1        # watch the signals go by
redis-cli SUBSCRIBE onlineorder:push:v1:probe  # a probe per pod every 30 s
```

Unknown message properties are ignored, so a release may add some. Any other change of the message format takes a new
channel suffix (`:v2`), like the session namespace: pods of the old and the new release then do not see each other's
signals during the rollout, which only costs the fallback polls. `PUSH_FANOUT=local` delivers in-process only (the
`h2` profile, tests, a single instance); with several pods it would leave every stream blind to the other pods' writes.

### Metrics

| Metric | Read it as |
| --- | --- |
| `onlineorder_push_streams` | Open streams on the pod, about one per open console tab; compare with `PUSH_MAX_STREAMS` |
| `rate(onlineorder_push_signals_total[5m])` by `topic` | Committed changes that published a signal (counted on the writing pod) |
| `rate(onlineorder_push_deliveries_total[5m])` by `topic` | Signals written to a stream (counted on the pod holding it); zero while signals rise means no stream listens or the subscription is down |
| `rate(onlineorder_push_publish_failures_total[5m])` | Signals lost before Redis: Redis unreachable or slow (`spring.data.redis.timeout`), or a full queue. Clients poll meanwhile |
| `onlineorder_push_subscribed` | 1 while the pod receives the channel's messages (its own probe, published every `PUSH_PROBE_INTERVAL` on the `:probe` channel, came back before the next one), 0 while it does not come back (not subscribed, while publishing fine) or cannot be published (Redis unreachable): either way the pod delivers nothing to its streams, and its consoles poll every 60 s. A constant 1 with `PUSH_FANOUT=local` |
| `http_server_requests_seconds_count{uri="/events/stream",status=~"429\|503"}` | Streams refused at a limit |

The dashboard row "Push signals (SSE)" plots them: open streams per instance, signals published and delivered per
topic, the publish failures with the refused streams, and the subscription stat (the share of the selected instances
whose `onlineorder_push_subscribed` is 1).

### Alerts

| Alert | Fires when | Do |
| --- | --- | --- |
| `OnlineOrderPushPublishFailures` | `onlineorder_push_publish_failures_total` rose in every 5-minute window for 10 minutes | The WARN log "Could not publish a push signal on Redis channel ...; clients fall back to polling" (one per streak) has the cause. Check Redis as for `OnlineOrderRateLimiterFailingOpen` (`PING`, latency, memory) and that the application's Redis user may `PUBLISH` on the channel (`ACL GETUSER <user>`). A Redis outage fires the rate-limit alert and breaks the sessions too; failures here alone are pub/sub or the `push-publisher` thread (its queue full behind a stuck publish shows in a thread dump). The INFO log "Push signals reach Redis channel ... again" ends the streak; the signals lost meanwhile are not replayed, the consoles' next poll shows the changes |
| `OnlineOrderPushSubscriptionDown` | `onlineorder_push_subscribed` of an instance has been 0 for 5 minutes: its own probe has not come back (not subscribed while publishing fine, or Redis unreachable), so it delivers nothing to its streams | That pod is not subscribed to `onlineorder:push:v1` (Redis restarted and Lettuce has not subscribed again, a channel renamed on one side) or cannot reach Redis: `redis-cli PUBSUB NUMSUB onlineorder:push:v1` should show one subscriber per running pod, and the pod's log has the WARN "Push subscription lost on channel ..." where it started and the INFO "Push subscription restored on channel ..." once it is back. Its streams get keepalives only meanwhile and its consoles poll every 60 s; nothing needs replaying. A pod that does not get the subscription back is restarted (a restart renews it). Pods of two releases with different `PUSH_CHANNEL` values each probe their own channel, so this does not see that split. A whole Redis outage fires `OnlineOrderPushPublishFailures` and the rate-limit alert too; this one alone is that pod's subscription or its path to Redis |
| `OnlineOrderPushStreamsRefused` | an instance answered `503` to stream connects in every 5-minute window for 10 minutes | `onlineorder_push_streams` of that instance near `PUSH_MAX_STREAMS` (2000): add pods or raise the limit (below `SERVER_TOMCAT_MAX_CONNECTIONS`). Otherwise the instance's login lookups cannot reach the database (the 5xx rate and the Hikari alerts show it too), or it has been shutting down for longer than its grace period. The refused browsers poll and try again after 30 s, doubling to 5 minutes; nothing needs replaying |

### Limits and capacity

A stream holds a connection, never a thread (servlet async). `PUSH_MAX_STREAMS` (2000 per pod) answers `503` above it,
or while the pod shuts down; keep it below `SERVER_TOMCAT_MAX_CONNECTIONS` (8192), which every other request shares.
`PUSH_MAX_STREAMS_PER_USER` (5) answers `429 PUSH_STREAM_LIMIT`; it counts per pod, so a login whose tabs land on
several pods may hold that many on each. A browser whose stream is refused polls at the old interval. A stream ends
after `PUSH_STREAM_TIMEOUT` (30m, its own async timeout, so `spring.mvc.async.request-timeout` does not apply) and the
browser reconnects after the `retry: 5000` it got first. On SIGTERM a pod completes its streams before Tomcat's
graceful shutdown starts, so they do not hold the shutdown and their browsers reconnect to another pod (the preStop
sleep has already taken the pod out of the endpoints).

### Proxies and the ingress

Each frame must reach the browser when it is written, and the connection must survive the 25 s between keepalives.
The response carries `Cache-Control: no-cache` and `X-Accel-Buffering: no` (nginx stops buffering it). On Kubernetes
`/events/stream` has its own Ingress, `onlineorder-events` in `deploy/k8s/base/ingress.yaml` (ingress-nginx applies
annotations per Ingress), with `proxy-buffering: "off"` and 120-second read and send timeouts; the staging overlay
patches its host and TLS secret with the main Ingress. Another proxy in front needs the same: no buffering or
compression of `text/event-stream`, and an idle timeout well above 25 s. A proxy that buffers shows as streams that open
(`onlineorder_push_streams` rises) while the consoles only change on their fallback polls.

### Checking a stream

```sh
curl -s -c /tmp/push-cookies -o /dev/null -w '%{http_code}\n' \
  --data-urlencode "username=$EMAIL" --data-urlencode "password=$PASSWORD" http://localhost:8080/login
curl -N -b /tmp/push-cookies -H 'Accept: text/event-stream' http://localhost:8080/events/stream
```

`EMAIL` and `PASSWORD` are a login of the role to check (locally, one of the demo merchants of
`db/demo/afterMigrate__seed_demo_fulfilment.sql`). The second command prints `retry: 5000` at once, `:keepalive` every 25 seconds and, for example after a paid order of
Burger King, `event: signal` with `data: {"topic":"merchant.orders"}`. `401` means the session cookie is missing,
`403` a deactivated merchant or rider, `429` or `503` a limit above. Without `-N` curl buffers the output, and nothing
appears until the stream ends.

### Failure modes

- Redis down: publishes fail (`onlineorder_push_publish_failures_total` grows, `OnlineOrderPushPublishFailures` after
  10 minutes of it, one warning per streak in the log) and the subscription waits for Lettuce to reconnect; the probe
  stops coming back, so `onlineorder_push_subscribed` drops to 0 within two probe intervals (the WARN "Push
  subscription lost on channel ...") and `OnlineOrderPushSubscriptionDown` fires after 5 minutes of it; streams stay
  open and get only keepalives, and the consoles poll every 60 s. When Redis is back the log says `Subscribed to push
  channel onlineorder:push:v1` and, once a probe came back, `Push subscription restored on channel ...`; signals
  published in between are lost, the next poll or signal shows the change.
- A deactivated merchant or rider: their stream gets `event: forbidden` within one keepalive interval, and their next
  connect is `403`; every merchant and rider REST request checks the flag anyway.
- A logout does not end the streams the browser had open (the client closes them; signals carry no data); the
  stream timeout bounds how long one stays open.
