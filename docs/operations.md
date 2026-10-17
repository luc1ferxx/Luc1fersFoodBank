# Operations

How OnlineOrder is observed, packaged, deployed and gated. Paths are relative to the repository root.

| Topic | Where |
| --- | --- |
| Tracing, logs, metrics | `backend/src/main/java/com/laioffer/onlineorder/observability/`, `application.yml` (`management.*`) |
| Local observability stack | `deploy/observability/` |
| Dashboards and alert rules | `deploy/observability/grafana/dashboards/`, `deploy/observability/prometheus/alerts.yml` |
| Kubernetes | `deploy/k8s/` (kustomize base + `staging` and `prod` overlays) |
| Image | `Dockerfile` |
| CI and CD | `.github/workflows/`, `.github/dependabot.yml`, `.trivyignore` |

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
| `onlineorder_outbox_backlog`, `onlineorder_outbox_events{status}`, `onlineorder_outbox_failed` | Outbox rows by status |
| `onlineorder_outbox_publish_total{result}` | Publisher results |
| `onlineorder_outbox_relay_seconds` | Time from relay start until the publisher has the Kafka acknowledgement or the failure |
| `onlineorder_dead_letter_stored_total`, `onlineorder_dead_letter_events{replay_status}`, `onlineorder_dead_letter_replay_total` | Dead letters |
| `onlineorder_checkout_requests_total{result}` | Checkout results |

`http.server.requests` publishes a percentiles histogram bounded to 5ms-10s plus exact SLO buckets at 50ms, 100ms,
250ms, 500ms, 1s and 2s. The latency objective reads the `le="0.5"` bucket.

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
| `OnlineOrderDeadLettersGrowing` | a dead letter stored in the last 15m, including the first one after a start (the counter series appears at 1); replay with `POST /dead-letters/{id}/replay` | warning |
| `OnlineOrderHikariPendingSustained` | threads waiting for a connection for 5m | warning |
| `OnlineOrderHikariConnectionTimeouts` | connection requests timed out (503) in the last 5m | warning |
| `OnlineOrderKafkaConsumerLag` | a consumer client > 1000 records behind for 10m | warning |

On Kubernetes, load the same file into Prometheus (or wrap it in a `PrometheusRule` for the Prometheus Operator) and
make sure the scrape job is named `onlineorder` or adjust `OnlineOrderTargetDown`.

Validate after editing:

```sh
cd deploy/observability/prometheus
docker run --rm --entrypoint /bin/promtool -v "$PWD:/etc/prometheus:ro" -w /etc/prometheus prom/prometheus:v3.13.4 check rules alerts.yml
docker run --rm --entrypoint /bin/promtool -v "$PWD:/etc/prometheus:ro" -w /etc/prometheus prom/prometheus:v3.13.4 test rules alerts.test.yml
docker run --rm --entrypoint /bin/promtool -v "$PWD:/etc/prometheus:ro" -w /etc/prometheus prom/prometheus:v3.13.4 check config prometheus.yml
```

Fulfilment staffing (README "Provisioning merchants and riders"):

- A dead-lettered `order.accepted` with `onlineorder_fulfilment_dispatch_assignments_total{result="no_rider_attempt"}`
  rising means no rider was active and on duty. Get one on duty first: the rider uses `PUT /rider/me/availability`, or
  an admin sets `PATCH /admin/riders/{id}` `{"on_duty": true}` (with `"active": true` for a deactivated rider; new and
  reactivated riders are off duty). Confirm with `GET /admin/riders`, then replay the dead letter; a replay before that
  fails again.
- Deactivating a rider leaves their open deliveries with them (`active_deliveries` in the response). List them with
  `GET /admin/deliveries?rider_id={id}` and move each with `POST /admin/deliveries/{id}/reassign` `{"rider_id": n}` to
  an active, on-duty rider, or reactivate the rider (an order still `ACCEPTED` can also be cancelled). Never clear them
  with the admin `delivered` endpoint: it completes the order.
- `onlineorder_fulfilment_merchant_orders_received_total{staffing="unstaffed"}` counts paid orders of restaurants with
  no active merchant account. They get no accept deadline and wait as `PAID` until a merchant is provisioned
  (`POST /admin/merchants`; the order is already in that restaurant's inbox) or an admin moves them.
- The accept timeout is on by default (`FULFILMENT_ACCEPT_TIMEOUT_ENABLED=true`) and only covers staffed restaurants.
- Who changed a staff account or a delivery is in `GET /admin/audit-log`: `merchant.create`, `merchant.update`,
  `rider.create`, `rider.update` and `delivery.reassign` with the admin, `rider.availability` and the rider's own
  `delivery.picked_up` / `delivery.delivered` with the rider's login (`ROLE_RIDER`).

## 3. Container image

The root `Dockerfile` builds the frontend in `node:24.22.0-bookworm-slim` (`npm ci`,
`npm run build`), the jar in `gradle:8.14.3-jdk17-noble` (the npm-based `buildFrontend` task is skipped there and the
stage's build output is embedded instead), extracts Spring Boot layers with `-Djarmode=tools`, and runs on
`eclipse-temurin:17.0.20.1_1-jre-noble` as uid 10001 with `JAVA_OPTS="-XX:MaxRAMPercentage=75.0
-XX:InitialRAMPercentage=50.0 -XX:+ExitOnOutOfMemoryError"` (override `JAVA_OPTS` to change them). Ports: 8080
application, 8081 management when the deployment sets `MANAGEMENT_SERVER_PORT=8081`.

`backend/Dockerfile` packages a jar built beforehand (`cd backend && ./gradlew bootJar`, then `docker build backend`)
on the same pinned Java 17 runtime, layered and non-root. Use `docker build --platform linux/amd64 backend` to build
for another architecture.

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

Keep `maxReplicas x DATABASE_POOL_MAX_SIZE` below PostgreSQL's `max_connections` (prod: 6 x 20). Flyway migrates on
startup; concurrent pods wait on Flyway's lock. Demo seed data (`db/demo`) is not enabled in either overlay.

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
| `JAVA_OPTS` | see the image | image |

## 5. CI gates

`.github/workflows/ci.yml` (push to main/master, pull requests, and called by the release):

- `backend-test`: `./gradlew test jacocoTestReport jacocoTestCoverageVerification spotbugsMain -x buildFrontend -x
  syncFrontendBuild --no-daemon`. Testcontainers suites run on the runner's Docker. The job summary shows the
  coverage table; JUnit XML, the HTML test report, JaCoCo and SpotBugs reports are uploaded as
  `backend-test-reports`, also on failure.
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

Neither `order_status_history` nor `audit_log` has a retention job (`MaintenanceCleanupService` leaves them alone):
both are records to keep. They grow by one row per status change and per audited admin or staff action. Query them by order
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

## 8. Money with currency (V11)

V11 (`backend/src/main/java/db/migration/V11__Money_with_currency.java`) gives every amount a currency and a fixed
scale; README "Money and currency" has the model. It creates the `currencies` reference table, adds
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
