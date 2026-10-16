# AGENTS.md

## Project Shape

- Full-stack food ordering app: React frontend in `frontend/`, Spring Boot backend in `backend/`.
- Backend uses Java 17, Gradle wrapper 8.14.3, PostgreSQL, Redis, Kafka, Spring Session, Actuator, Flyway, and Testcontainers.
- Frontend is React 19 built with Vite 8 (`@vitejs/plugin-react`), tested with Vitest + jsdom + React Testing Library, and linted with ESLint (flat config, react-hooks rules) + Prettier. No UI component library. CI and Docker use Node 20 (the locked Vite, Vitest, ESLint, and jsdom together need Node `^20.19.0 || ^22.13.0 || >=24`).

## Common Commands

### Local infrastructure

```sh
cd backend
docker compose up -d
```

This starts PostgreSQL on `localhost:5433`, Redis on `localhost:6379`, and Kafka on `localhost:9092`.

### Backend

```sh
cd backend
./gradlew test -x buildFrontend -x syncFrontendBuild --no-daemon
```

Use the command above for the normal backend verification path; it matches CI and avoids rebuilding the React app for backend-only changes.

```sh
cd backend
./gradlew bootRun
```

Flyway applies schema migrations from `src/main/resources/db/migration` on startup. Normal startup is non-destructive and does not drop user data.

For local/demo seed data on PostgreSQL, include the demo Flyway callback location:

```sh
cd backend
FLYWAY_LOCATIONS=classpath:db/migration,classpath:db/demo ./gradlew bootRun
```

`INIT_DB=always` is no longer used for schema reset. For a disposable local reset, stop the backend, confirm the local database has no data that must be kept, then recreate the `onlineorder` database or delete only the PostgreSQL Compose volume declared in `backend/docker-compose.yml` as `onlineorder-pg-local`. Rerun the backend afterward so Flyway can apply migrations again.

```sh
cd backend
SPRING_PROFILES_ACTIVE=h2 ./gradlew bootRun
```

Use the `h2` profile as a lightweight backend fallback without PostgreSQL or Kafka. Redis is still configured as the cache backend.
The `h2` profile uses Flyway migrations plus the idempotent demo seed callback by default.

### Frontend

```sh
cd frontend
npm ci
npm start
```

The Vite dev server runs on `http://localhost:3000` and proxies the backend path prefixes listed in `frontend/src/api/proxyPaths.js` to `http://localhost:8080` (override with `DEV_PROXY_TARGET`). When the frontend starts calling a new backend path, add its prefix there; `src/api/client.test.js` fails otherwise.

```sh
cd frontend
npm run lint   # ESLint + prettier --check
npm test       # Vitest, single run
npm run build  # vite build into frontend/build, then scripts/verify-csp-build.js
```

- The backend serves the build with `script-src 'self'`, so `build/index.html` must not contain inline scripts; the `postbuild` check and `src/cspBuild.test.js` enforce it. Hashed assets go to `build/static/` because the backend only permits anonymous GETs for `/static/**`.
- Client-side env vars must be prefixed `VITE_` and read through `import.meta.env` (none are used today).
- Layout: `src/api/` (fetch wrapper with AbortSignal support, `ApiError`, central 401 -> `onUnauthorized`, endpoint functions that take an `Idempotency-Key`), `src/hooks/` (`useSession`, `useMenu`, `useCart`, `useCheckout`, `useOrders`, `useNotifications`, `useAdminTools`), `src/components/`, and `src/pages/OrderingPage.jsx`, which `App.jsx` keys by the signed-in user so session-scoped state resets on login, logout, or expiry. `src/test/fakeBackend.js` is the in-memory API used by the UI tests.

### Packaging and embedded frontend assets

```sh
cd frontend
npm ci
cd ../backend
./gradlew bootJar --no-daemon
```

`bootJar` triggers the Gradle `buildFrontend` and `syncFrontendBuild` tasks through `processResources`, so frontend dependencies must already be installed.

```sh
cd frontend
npm run deploy:backend
```

This manually builds the frontend and copies `frontend/build` into `backend/src/main/resources/public`.

## CI and Release Workflows

- `.github/workflows/ci.yml` runs:
  - `npm ci`, `npm run lint`, `npm test`, and `npm run build` in `frontend/`
  - `./gradlew test -x buildFrontend -x syncFrontendBuild --no-daemon` in `backend/`
  - `./gradlew bootJar --no-daemon` after installing frontend dependencies
- `.github/workflows/release.yml` builds and publishes the root `Dockerfile` image to GHCR on `v*` tags or manual dispatch.
- Prefer the root `Dockerfile` for a full source build. `backend/Dockerfile` expects `backend/build/libs/OnlineOrder-0.0.1-SNAPSHOT.jar` to already exist and packages it on the same pinned, non-root Java 17 runtime (`docker build --platform linux/amd64 backend` for another architecture).

## Testing Notes

- Backend integration tests use Testcontainers for PostgreSQL, Kafka, and related flows; Docker must be available for those tests to run.
- Several Testcontainers tests are annotated with `@Testcontainers(disabledWithoutDocker = true)`, so Docker-dependent tests may skip locally when Docker is unavailable.
- Load testing is defined in `load-tests/order-flow.js` and expects a running backend:

```sh
k6 run load-tests/order-flow.js
```

Optional environment variables include `BASE_URL`, `VUS`, `DURATION`, `USER_COUNT`, `USER_PASSWORD`, and `USER_PREFIX`.

`load-tests/catalog-read.js` is an anonymous read-only benchmark for the cached catalog endpoints (`BASE_URL`, `VUS`, `DURATION`; `CONDITIONAL=true` sends `If-None-Match`). Run it against the isolated compose project in `load-tests/catalog-bench.compose.yml` (ports 45433/46379; usage in the file header) and remove that project afterwards with `docker compose -f load-tests/catalog-bench.compose.yml down`.

## Edit Guidelines

- Do not hand-edit generated frontend build output under `frontend/build/` or copied backend static output under `backend/src/main/resources/public/`; regenerate it through the frontend build/deploy commands when intentionally updating embedded assets.
- Keep schema changes in versioned Flyway migrations under `backend/src/main/resources/db/migration`. Put non-destructive local/demo seed changes in `backend/src/main/resources/db/demo` when they are not required production schema.
- DDL that H2 cannot run (partial indexes, `CREATE INDEX CONCURRENTLY`) goes in a versioned Java migration under `backend/src/main/java/db/migration` that only records its version on other databases; `V3__Add_outbox_unpublished_index` is the pattern. The `h2` profile runs the same migration list.
- Catalog caching (`backend/src/main/java/com/laioffer/onlineorder/cache`, see README "Catalog Caching"): cached values are the `Catalog*Dto` records; bump the key version in `CatalogCaches` when one changes shape. Catalog writes must evict through `CatalogCacheInvalidator` (after commit), never by writing into the cache. New caches need a `TwoLevelCacheSpec`; unknown cache names are rejected. Redis outages are tested both refused (`CatalogRedisOutageIntegrationTests`) and hung (`CatalogRedisHangIntegrationTests`, which pauses its own Redis container); keep the hung case within its latency budget when changing the read path.
- Treat checkout, cart mutation, order status changes, outbox publishing, idempotency, and DLT replay as correctness-sensitive paths; update focused tests when changing them.
- In the frontend, a retry of the same checkout, cancel, or status change must reuse its `Idempotency-Key` (see `useCheckout`, `useOrders`, `useAdminTools`), and responses for superseded requests must be dropped through `useLatestRequest`; `src/App.test.jsx` covers both.

## Order Fulfilment Modules

- After payment the order lifecycle is event-driven (README "Order fulfilment"): `fulfilment.merchant` (inbox, accept/reject/preparing, merchant accounts, `merchant-events` topic), `fulfilment.timeout` (`order_deadlines` + deadline poller), `fulfilment.dispatch` (riders and the rider API `/rider/**`, deliveries, `delivery-events` topic), `fulfilment.order` (completion on `delivery.delivered`). Migrations `V5`-`V5_4` and `V8` (staff accounts: `merchant_accounts.active`, `riders.customer_id`/`on_duty`) belong to them.
- Modules talk through events only. Their one synchronous seam is `OrderLifecycleCommands` (implemented by `OrderService`); never read or write another module's tables. New events go through `OrderEventOutboxService.enqueueEvent`.
- Every consumer has its own consumer group, uses `orderEventKafkaListenerContainerFactory`, and is idempotent (`processed_events` mark in the same transaction plus a unique key). Merchant commands, timeouts, dispatch assignment and delivery completion are correctness-sensitive; the Postgres tests are `FulfilmentPostgresIntegrationTests`, the Kafka end-to-end tests are `FulfilmentFlowContainerTests` (long accept timeout) and `FulfilmentTimeoutContainerTests` (short timeouts), and staff accounts, the role matrix and rider IDOR are in `StaffAccountsPostgresIntegrationTests`. Kafka container test classes carry `@DirtiesContext` so a cached context's consumers cannot join a later class's broker on a reused port. Consumers must also stay correct when a replayed dead letter arrives after later events of the same order (README "Out-of-order events").
- The deadline poller runs on its own thread, not the shared `spring.task.scheduling` pool, and follows `app.kafka.enabled` (off in the `h2` profile). Do not register another `TaskScheduler` bean: Spring Boot would back off its scheduler for all `@Scheduled` jobs.
- Demo merchants (`merchant.burgerking@laifood.com` etc., password `merchant123`) and riders with logins (`rider.alice@laifood.com` etc., password `rider123`, on duty) come from `db/demo/afterMigrate__seed_demo_fulfilment.sql` and are demo-only.
- Merchant and rider logins are provisioned by admins (`/admin/merchants`, `/admin/riders`) through `com.laioffer.onlineorder.staff`, which creates the login with `CustomerService` and binds it through `MerchantAccountService` / `RiderService` in one transaction. Deactivation is enforced by re-checking the active flag on every merchant and rider request (403), not by invalidating sessions; keep that check in any new merchant or rider endpoint. A rider only reaches their own deliveries (another rider's is 404). Dispatch assigns only active, on-duty riders; rider flag writes keep `on_duty` false for an inactive rider (the rider's own write is guarded by `active = TRUE`, the admin PATCH locks the row). A deactivated rider's open deliveries are moved with `POST /admin/deliveries/{id}/reassign`, never by marking them delivered.
- The merchant accept timeout is on by default (`app.fulfilment.accept-timeout-enabled`) but only for staffed restaurants: the merchant inbox publishes `merchant.order_received` (merchant topic) when the restaurant has an active merchant account, and the timeout module sets the `ACCEPT` deadline from that event only (`order.paid` just drops the `PAYMENT` deadline), measured from the event time (when the inbox received the order), not the payment time. The timeout module must not read merchant tables.

## Observability and Delivery

Details in `docs/operations.md`.

- Tracing is Micrometer Tracing + OpenTelemetry with W3C `traceparent`. `X-Trace-Id` and error-body `trace_id` are the OpenTelemetry trace id. Spans are exported only with `TRACING_EXPORT_ENABLED=true`; tests never export, and Spring Boot tests turn propagation off unless the class has `@AutoConfigureObservability` (needed to assert trace ids).
- Insert outbox rows through `OutboxEventRepository.save(...)`: `OutboxTraceContextCallback` stores the current traceparent in `outbox_events.trace_parent` (V6) and the publisher continues that trace. A hand-written `INSERT` loses the trace. Kafka observation is enabled for every `KafkaTemplate` and listener container factory bean by `TracingConfig`, including new ones.
- `MANAGEMENT_SERVER_PORT=8081` (Kubernetes ConfigMap, the `app` profile of `deploy/observability`; not baked into the image) serves the actuator on its own port without a session; unset, the actuator stays on 8080 behind `ROLE_ADMIN` (local runs, tests, plain `docker run`). Point k6 at a split management port with `MANAGEMENT_URL`.
- CI's `backend-test` job runs `./gradlew test jacocoTestReport jacocoTestCoverageVerification spotbugsMain -x buildFrontend -x syncFrontendBuild --no-daemon` (coverage minimums in `backend/build.gradle`; SpotBugs baseline in `backend/config/spotbugs/exclude.xml`). Its `config-lint` job validates `deploy/` and the workflows. `release.yml` runs CI, a Trivy gate (dated exceptions in `.trivyignore`; security patch overrides at the top of `backend/build.gradle`), an SBOM, the GHCR push and kustomize deploys (staging only with a `KUBE_CONFIG` secret; production through the protected environment).
- Validate observability and Kubernetes changes with promtool (`check rules`, `test rules alerts.test.yml`, `check config`), `docker compose -f deploy/observability/docker-compose.yml --profile app config`, and `kubectl kustomize deploy/k8s/overlays/<env>` + kubeconform; the exact commands are in `docs/operations.md` and the `config-lint` job.
