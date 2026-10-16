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
  http post /payments/checkout -> <topic> outbox relay -> <topic> send -> <topic> receive -> consumer work
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

Sampling (`TRACING_SAMPLING_PROBABILITY`, default 0.1) decides which new traces are recorded; downstream spans
follow the caller's decision. Trace ids exist for unsampled requests too. Spans are exported over OTLP/HTTP only when
`TRACING_EXPORT_ENABLED=true`; local runs and tests never export (`OutboxKafkaTraceContinuityTests` uses an in-memory
exporter).

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

Verified on 2026-10-16 with the app profile: a checkout produced one trace in Tempo with the server span, security
spans, `order-events outbox relay`, `order-events send` and `order-events receive`; Prometheus had all four targets up,
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

## 3. Container image

The root `Dockerfile` builds the frontend in `node:20.20.2-bookworm-slim` (`npm ci`,
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

State on 2026-10-16: the image scan found 45 fixable HIGH/CRITICAL findings with Spring Boot 3.5.6, pgjdbc 42.7.7
and the `17.0.20_8` base image. After Spring Boot 3.5.16, pgjdbc 42.7.14, the `17.0.20.1_1` base image and the
patch overrides at the top of `backend/build.gradle` (Tomcat 10.1.61, Netty 4.1.139, Jackson 2.21.7, lz4-java
1.11.4) it passes the gate with two accepted exceptions in `.trivyignore`: CVE-2026-47884 and CVE-2026-47890 in
spring-webmvc 6.2.19, fixed only in Spring Framework 7 (the Spring Boot 4 migration) and not reachable here (no XSLT
views, no Server-Sent Events with view fragments). They expire on 2027-01-31, when the gate fails again unless they
are fixed or reviewed. The overrides pin versions above what Spring Boot manages: drop each once Spring Boot manages
that version or a later one, or it holds the dependency back.
