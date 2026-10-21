# AGENTS.md

## Project Shape

- Full-stack food ordering app: React frontend in `frontend/`, Spring Boot backend in `backend/`.
- Backend uses Java 17, Spring Boot 4.1 (Spring Framework 7, Spring Security 7, Spring Kafka 4), Gradle wrapper 8.14.3, PostgreSQL, Redis, Kafka, Spring Session, Actuator, Flyway, and Testcontainers 2. JSON is Jackson 3 (`tools.jackson.*`; the annotations stay `com.fasterxml.jackson.annotation`). `docs/spring-boot-4-migration.md` lists every package, test-annotation and property change from Spring Boot 3; apply it to code written against Spring Boot 3.
- Frontend is React 19 in strict TypeScript (6.0; typescript-eslint supports `<6.1`, so not the TypeScript 7 native compiler yet) built with Vite 8 (`@vitejs/plugin-react`), tested with Vitest + jsdom + React Testing Library, and linted with ESLint (flat config, type-aware typescript-eslint, react-hooks rules) + Prettier. No UI component library. CI and Docker use Node 24 LTS (the locked Vitest 5, jsdom 30, ESLint, and Vite together need Node `^22.22.2 || ^24.15.0 || >=26.0.0`; `engines` in `frontend/package.json` states it).

## Common Commands

### Local infrastructure

```sh
cd backend
docker compose up -d
```

This starts PostgreSQL on `localhost:5433`, Redis on `localhost:6379`, and Kafka on `localhost:9092`. `docker compose --profile replica up -d` also starts a streaming replica of it on `localhost:5434`; the backend reads it only with `DATABASE_REPLICA_URLS=jdbc:postgresql://localhost:5434/onlineorder` (read/write splitting, `docs/operations.md` section 11).

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
`SPRING_PROFILES_ACTIVE=h2,demo` adds `application-demo.yml`: a 2h after-sales request window, answer deadlines of minutes instead of hours, and the after-sales poller on although Kafka is off (README "After-sales demo"). Without Kafka the merchant's after-sales inbox stays empty, so merchants cannot answer; the merchant timeout still approves.

### Frontend

```sh
cd frontend
npm ci
npm start
```

The Vite dev server runs on `http://localhost:3000` and proxies the backend path prefixes listed in `frontend/src/api/proxyPaths.ts` to `http://localhost:8080` (override with `DEV_PROXY_TARGET`). When the frontend starts calling a new backend path, add its prefix there; `src/api/client.test.ts` fails otherwise.

```sh
cd frontend
npm run typecheck  # tsc --noEmit on tsconfig.app.json (src) and tsconfig.node.json (vite.config.ts, scripts/)
npm run lint       # ESLint + prettier --check
npm test           # Vitest, single run
npm run build      # vite build into frontend/build, then scripts/verify-csp-build.js
```

- TypeScript is `strict` with `noUncheckedIndexedAccess`. Backend JSON enters as `unknown` (`request()` in `src/api/client.ts`) and is decoded into the DTOs of `src/api/dto.ts` with the readers in `src/api/decode.ts`; ESLint rejects `any`, `as` casts (`as const` is fine), and `!`. A new endpoint gets a DTO interface plus decoder next to the others, matching the backend record's snake_case fields (nullable Java fields are `T | null`; the backend omits nulls). The after-sales decoders (`decodeAfterSalesCase`, `decodeRefund`, `decodeMerchantAfterSalesCase`, ...) read every status, kind, refund status and history `action` against the constant lists in `dto.ts` (`AFTER_SALES_STATUSES`, `AFTER_SALES_ACTIONS`, ...) and fail on an unknown one, so a new backend value ships after the frontend that knows it. `scripts/*.js` stay plain JavaScript run directly by Node and are type-checked through JSDoc (`checkJs`).
- The backend serves the build with `script-src 'self'`, so `build/index.html` must not contain inline scripts; the `postbuild` check and `src/cspBuild.test.ts` enforce it. Hashed assets go to `build/static/` because the backend only permits anonymous GETs for `/static/**`.
- Client-side env vars must be prefixed `VITE_` and read through `import.meta.env` (none are used today).
- Layout: `src/api/` (fetch wrapper with AbortSignal support, `ApiError` with status/code/trace id, central 401 -> `onUnauthorized`, typed endpoint functions, some taking an `Idempotency-Key`), `src/hooks/`, `src/components/`, `src/lib/`, and `src/pages/`. `App.tsx` picks workspaces from the `/me` roles (`src/lib/workspaces.ts`): `OrderingPage` (customers and guests), `MerchantConsole` (`ROLE_MERCHANT`), `RiderConsole` (`ROLE_RIDER`), `AdminConsole` (`ROLE_ADMIN`: merchant/rider provisioning plus the order and DLT tools); a user with several roles switches between them. The workspaces sit in a shell keyed by the signed-in user, so session-scoped state and the workspace choice reset on login, logout, or expiry (also when the same user signs in again). A switch only hides `OrderingPage` and `AdminConsole` (`KEPT_MOUNTED` in `App.tsx`), so their retry keys and forms survive; the merchant and rider consoles remount. `src/test/fakeBackend.ts` (with `fakeFulfilment.ts`, `fakeAdmin.ts`, `fakeAfterSales.ts`, `fixtures.ts`) is the in-memory API used by the UI tests and models the role checks; `fakeAfterSales.ts` also copies the after-sales transitions, the 409 codes and messages word for word, the hard deadlines and the idempotent replays, so keep it in step with the backend (it runs no timeouts and shows a new case to the merchant at once, unlike the Kafka-fed inbox). `src/test/helpers.tsx` renders the app.

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
  - `npm ci`, `npm run typecheck`, `npm run lint`, `npm test`, and `npm run build` in `frontend/`
  - `./gradlew test -x buildFrontend -x syncFrontendBuild --no-daemon` in `backend/`
  - `./gradlew bootJar --no-daemon` after installing frontend dependencies
- `.github/workflows/release.yml` builds and publishes the root `Dockerfile` image to GHCR on `v*` tags or manual dispatch.
- Prefer the root `Dockerfile` for a full source build. `backend/Dockerfile` expects `backend/build/libs/OnlineOrder-0.0.1-SNAPSHOT.jar` to already exist and packages it on the same pinned, non-root Java 17 runtime (`docker build --platform linux/amd64 backend` for another architecture).

## Testing Notes

- Backend integration tests use Testcontainers for PostgreSQL, Kafka, and related flows; Docker must be available for those tests to run.
- Several Testcontainers tests are annotated with `@Testcontainers(disabledWithoutDocker = true)`, so Docker-dependent tests may skip locally when Docker is unavailable.
- Race tests on PostgreSQL do not rely on thread timing: `PostgresRowLocks` holds the contested row, queues the two commands behind it in a chosen order and releases it, and the tests run both serial orders (alternating) so each outcome is reached on every run. They depend on the pool's `lock_timeout` (3000 ms, `application.yml`) and on nothing else using the database meanwhile (background jobs off). Write new race tests the same way.
- The test JVM heap is 1 GB (`maxHeapSize` in the `test` task of `backend/build.gradle`): Spring caches one context per distinct test configuration, and Gradle's 512 MB default ran out once the after-sales suites joined.
- That the context holds exactly one `TaskScheduler` (the Spring Boot one) is asserted in `FulfilmentFlowContainerTests`, `AfterSalesDeadlinePostgresIntegrationTests` and, without Docker, `AfterSalesDemoProfileTests`.
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
- Treat checkout, cart mutation, order status changes, outbox publishing, idempotency, DLT replay, after-sales steps and refund execution as correctness-sensitive paths; update focused tests when changing them.
- State machines (`com.laioffer.onlineorder.statemachine`, ADR 0002): the order (`model.OrderStateMachine`), delivery (`fulfilment.dispatch.DeliveryStateMachine`), after-sales case and refund (`aftersales.AfterSalesStateMachine`, `RefundStateMachine`) lifecycles are each one `static final DEFINITION` from `StateMachine.builder(...)`, whose `build()` fails class initialization on an invalid table. Each has exhaustive table tests (every trigger from every state, the actors, the guards, `toMermaid()`); change the declaration and its table together. `decide(current, trigger, actor, context)` is pure (no IO; order: actor, alreadyDoneWhen, from-state, guards). The caller passes the trigger of its command and never infers it from the actor (an actor the transition does not list is an `IllegalStateException`, a 500). Write path: lock the row (`FOR UPDATE`, or a `SKIP LOCKED` claim, which never waits), read `now` once the row is held when a guard reads time (a command that waited for the lock is judged when it got it), decide, then write with a compare-and-set on the state (and version) read, checked with `CompareAndSet.expectOneRow`; a `StaleTransitionException` means a writer skipped the lock, so fix that writer instead of retrying. Tell `Decision.Apply` / `AlreadyDone` / `Rejected` apart with `instanceof` patterns, not switch patterns (Java 17), and map a `Rejected` to the caller's own 409.
- Read replicas (`backend/src/main/java/com/laioffer/onlineorder/datasource`, `docs/operations.md` section 11; off unless `DATABASE_REPLICA_URLS` is set): a read path opts in with `@Transactional(readOnly = true)` on a service method, or `ReadOnlyTransactions.read(...)` inside a `@Cacheable` method (a cache hit must open no transaction) or after a check that must read the primary. Annotate read-only queries that way; never put writes, `FOR UPDATE`, idempotency checks, outbox claims or an authorization check whose staleness matters (the merchant and rider active flag) in a read-only transaction. Statements outside a transaction and Spring Data's own repository transactions (the implicit ones around CRUD finders, and any declared on a repository method) use the primary, so annotate the service method. A session reads the primary for `sticky-window` after its own write; a request keeps the replica (or the primary) its first read used. A replica's routability is a deadline (`max-lag` after the last moment it was known current), so `max-lag` bounds every replica read; derive any staleness allowance from `ReplicaRoutingProperties.maxStaleness()`. Real-replica tests: `ReadReplicaRoutingIntegrationTests`, `ReadReplicaSessionStoreOutageTests` and `FulfilmentReplicaRoutingContainerTests` (`PostgresReplicationContainers`, from `backend/postgres/`); the rest of the suite runs without replicas. A test that runs transactions directly on a MockMvc test's thread is inside one mock request for the whole method (Spring's test listener binds it), so reset `RequestContextHolder` around reads meant as separate requests.
- Keys are 64-bit since V7: new tables use `BIGSERIAL`/`BIGINT` keys and `BIGINT` references. Every order status change goes through `OrderService`'s guarded transition, which writes the `order_status_history` row (with an `OrderActor`) and `orders.updated_at` in the same transaction; never update `orders.status` elsewhere. `OrderLifecycleCommands` has no overloads without an `OrderActor`: name the merchant, rider or `OrderActor.system()`. A new privileged admin action, or a staff change to an account or delivery (provisioning, activation, rider duty, a rider's own delivery move), calls `AuditLogService.record(...)` from the transactional service method that applies it (propagation `MANDATORY`), only when something changed and never with a password in the details, and gets a name in `AuditActions` (`AuditLogIntegrationTests`, `StaffAuditIntegrationTests`). Rollout notes for these migrations (no pods of an older version next to them, online key widening): `docs/operations.md` section 7; its operator SQL lives in `backend/src/main/resources/db/ops`, which Flyway never runs.
- Data tier (docs/operations.md section 9): on PostgreSQL `audit_log`, `order_status_history` and `processed_events` are partitioned by month (V10; H2 keeps plain tables). Their primary keys include the partition column, and no unique constraint can span the partitions: never rely on one there. `processed_events` marks go only through `ProcessedEventRepository.insertIfAbsent` (`ProcessedEventDedupImpl`: advisory lock on the key plus a check across partitions, READ COMMITTED only); `ProcessedEventDedupAcrossPartitionsPostgresTests` covers it. Retention of these tables is `PartitionMaintenanceService` (whole months, advisory-locked; `MaintenanceCleanupService` deletes rows only where they are not partitioned). A new high-volume append-only table follows the same pattern (a Java migration, `PartitionedTable`, the maintenance job); `outbox_events` is the next candidate. Queries on them should filter on the partition column where they can.
- Orders carry a public `order_no` (V10_1): a Snowflake id from `DistributedIdGenerator`, assigned on insert by `OrderNumberCallback`, returned as a JSON string (`@JsonFormat(shape = STRING)`; JavaScript loses digits past 2^53; the frontend decodes it as `order_no: string | null` and labels orders with `orderLabel`) and used in notification texts; the internal `id` stays the key of every table and path. Customer-facing UI shows `order_no`, never the internal id; the merchant and rider consoles still show the id until order events carry `order_no`. A raw SQL insert into `orders` must set `order_no` (NOT NULL, UNIQUE). Every instance needs its own worker id: `app.ids.worker-id-source=static` with `APP_IDS_WORKER_ID` (default 0; tests and `h2`), or `lease` (Kubernetes ConfigMap; `WorkerIdLeaseManager`, PostgreSQL only). Worker id 1023 belongs to the V10_1 backfill.
- Sharding is designed but deferred (`docs/adr/0001-order-sharding.md`, shard key `customer_id`). Do not make it harder: no new synchronous seams between modules besides `OrderLifecycleCommands`, no new foreign keys from order-domain tables to the catalog, identity or module tables, and order-scoped data keeps a way to reach `customer_id`.
- Money (README "Money and currency", V11): every amount is `NUMERIC(12,2)` (non-negative check) with an ISO 4217 `currency CHAR(3)` next to it (supported codes: the `currencies` table, which `restaurants.currency` references; a new one is a migration inserting its row, see `docs/operations.md` section 10, also for the V11 rollout without older pods; migrations never round amounts), and Java computes with `com.laioffer.onlineorder.money.Money` only (`BigDecimal` scale 2, `HALF_EVEN` where it rounds, currency-checked), never `double`/`float` or SQL casts to them. A restaurant prices in one currency; carts and orders inherit it (composite keys tie a cart line to its menu item's and its cart's currency, so an empty cart is the only one that can change currency). A new amount column gets a `currency` column and a non-negative check; a new money field in a response gets a sibling `currency`; a new cached `Catalog*Dto` field bumps the cache key version. Order events carry `data.currency` (lines carry none; consumers read a missing one as USD). Frontend: decode currencies with `fieldsOf().currency`, format only with `formatMoney`, multiply by quantities with `lineTotal`.
- Redis sessions are JDK-serialized under a versioned namespace (`spring.session.data.redis.namespace`, suffix `:v2`) so pods of two releases never read each other's sessions during a rollout or rollback. When `RedisSessionFormatTests` fails after a dependency upgrade, bump the suffix (see `docs/spring-boot-4-migration.md`, "Sessions across releases").
- Spring Boot 4 runtime rules: JSON input records (request bodies, consumed event envelopes) use boxed component types, since Jackson 3 rejects a missing primitive (`JsonInputTypesTests`); code that lists roles from `getAuthorities()` keeps only `ROLE_*`, since Spring Security 7 adds `FACTOR_PASSWORD` to every password login.
- In the frontend, a retry of the same checkout, cancel, or status change must reuse its `Idempotency-Key` (see `useCheckout`, `useOrders`, `useAdminTools`), and responses for superseded requests must be dropped through `useLatestRequest`; `src/App.test.tsx` covers both. The after-sales customer commands (`useAfterSales`, one key per slot, `open:{orderId}:{attempt}` or `{command}:{caseId}`) and the arbitration (`useAfterSalesAdmin`) do the same: a retry with the same payload reuses the key, another payload gets a new one, success drops it; the second request after a withdrawal is another slot, so it never reuses the first request's key (that would replay the withdrawn case). Switching workspaces must not drop those keys (`src/Workspaces.test.tsx`). Merchant and rider commands, the merchant's after-sales answers and the refund retry send no key (the backend makes them idempotent by state). Polled lists go through `useLiveResource`, whose `applyAndReload` also drops a read that started before a command's result; a 403 there (the backend's answer for a deactivated merchant or rider) stops the polling and shows a notice instead. The customer's after-sales overview polls (10 s) only while a case is open or refunding; the order list does not poll, and `OrderingPage` reads it and the overview again when a notification read brings a newer notification id than any earlier read (`useNotifications`' `onNewNotifications`), so an order that just completed gets "Request a refund" without a refresh and without another timer (a cancel re-reads the orders, dropping a read from before it). The merchant's after-sales inbox polls like the order inbox (15 s); the admin after-sales section does not poll. `src/pages/*.test.tsx` cover the consoles, including stale responses and deactivation.

## Order Fulfilment Modules

- After payment the order lifecycle is event-driven (README "Order fulfilment"): `fulfilment.merchant` (inbox, accept/reject/preparing, merchant accounts, `merchant-events` topic, the after-sales inbox and answers), `fulfilment.timeout` (`order_deadlines` + deadline poller), `fulfilment.dispatch` (riders and the rider API `/rider/**`, deliveries, `delivery-events` topic), `fulfilment.order` (completion on `delivery.delivered`). Migrations `V5`-`V5_4` and `V8` (staff accounts: `merchant_accounts.active`, `riders.customer_id`/`on_duty`) belong to them.
- Modules talk through events only. Their one synchronous seam is `OrderLifecycleCommands` (implemented by `OrderService`); never read or write another module's tables. New events go through `OrderEventOutboxService.enqueueEvent(topic, key, envelope)` inside the producing transaction (propagation `MANDATORY`), with a published contract (see "Event contracts" below).
- Every consumer has its own consumer group, uses `orderEventKafkaListenerContainerFactory`, and is idempotent (`processed_events` mark in the same transaction plus a unique key). Merchant commands, timeouts, dispatch assignment and delivery completion are correctness-sensitive; the Postgres tests are `FulfilmentPostgresIntegrationTests`, the Kafka end-to-end tests are `FulfilmentFlowContainerTests` (long accept timeout), `FulfilmentTimeoutContainerTests` (short timeouts) and `AfterSalesFlowContainerTests` (the after-sales flows through the three roles' APIs, short merchant deadline), and staff accounts, the role matrix and rider IDOR are in `StaffAccountsPostgresIntegrationTests`. Kafka container test classes carry `@DirtiesContext` (the fulfilment and after-sales ones through `FulfilmentContainerTestSupport`, which a new one extends) so a cached context's consumers cannot join a later class's broker on a reused port. Consumers must also stay correct when a replayed dead letter arrives after later events of the same order (README "Out-of-order events").
- The deadline poller runs on its own thread, not the shared `spring.task.scheduling` pool, and follows `app.kafka.enabled` (off in the `h2` profile). Do not register another `TaskScheduler` bean: Spring Boot would back off its scheduler for all `@Scheduled` jobs.
- Demo merchants (`merchant.burgerking@laifood.com` etc., password `merchant123`) and riders with logins (`rider.alice@laifood.com` etc., password `rider123`, on duty) come from `db/demo/afterMigrate__seed_demo_fulfilment.sql` and are demo-only.
- Merchant and rider logins are provisioned by admins (`/admin/merchants`, `/admin/riders`) through `com.laioffer.onlineorder.staff`, which creates the login with `CustomerService` and binds it through `MerchantAccountService` / `RiderService` in one transaction. Deactivation is enforced by re-checking the active flag on every merchant and rider request (403), not by invalidating sessions; keep that check in any new merchant or rider endpoint. A rider only reaches their own deliveries (another rider's is 404). Dispatch assigns only active, on-duty riders; rider flag writes keep `on_duty` false for an inactive rider (the rider's own write is guarded by `active = TRUE`, the admin PATCH locks the row). A deactivated rider's open deliveries are moved with `POST /admin/deliveries/{id}/reassign`, never by marking them delivered. Provisioning, activation, duty changes and reassignments are audited (see the 64-bit keys bullet in Edit Guidelines). The demo seed grants staff roles only to logins it created (seeded `{noop}` password).
- The merchant accept timeout is on by default (`app.fulfilment.accept-timeout-enabled`) but only for staffed restaurants: the merchant inbox publishes `merchant.order_received` (merchant topic) when the restaurant has an active merchant account, and the timeout module sets the `ACCEPT` deadline from that event only (`order.paid` just drops the `PAYMENT` deadline), measured from the event time (when the inbox received the order), not the payment time. The timeout module must not read merchant tables.

## After-Sales Module

README "After-Sales Refunds"; design in `docs/adr/0002-after-sales-state-machine.md` (sections 19 and 20 amend the earlier ones, 21-26 record what was built, 27 sums it up with the deviations and open follow-ups; where the ADR and the code differ, the code is right). Migrations `V12` (tables, `order_payments`, `order_notifications.dedup_scope`), `V12_1` (currency checks, Java), `V12_2` (`idempotency_requests.case_id`) and `V12_3` (`refunds.claim_failures`) belong here.

- `com.laioffer.onlineorder.aftersales` belongs to the order domain and owns `after_sales_cases` (+ `_lines`, `_history`) and `refunds`. `AfterSalesTransitions` is the only writer of `after_sales_cases.status`: it inserts cases (`open`, `openOrderCancelled`) and takes every step (`fire`: decide, CAS on id + status + version, history row with the actor, the refund when entering `REFUNDING`, the event), in the caller's transaction (propagation `MANDATORY`). A refund row is inserted only there. Its status changes only as `RefundStateMachine` decides (in `RefundExecutor`, or the admin retry, `ADMIN_RETRY` in `AfterSalesAdminService`), each saved under the row's `@Version`. The only other write is the back-off of a failed claim (`RefundRepository.backOffPending` / `backOffExpiredLease`): it moves `next_attempt_at` or `claimed_until` and `last_error` only at the claimed version, keeps the status and attempts, and does not bump the version.
- Package `service` (and `model`) must not import `aftersales`. The order module owns the interfaces and `aftersales` implements them: `service.CancellationRefunds` (`OrderCancellationRefunds`, called by `OrderService` in every PAID/ACCEPTED -> CANCELLED transaction) and `service.AfterSalesMerchantCommands` (`AfterSalesMerchantDecisions`, reached through `OrderLifecycleCommands.decideAfterSalesCase`); the notification code in `service` compares after-sales kinds, actions and resolutions as strings for the same reason. The merchant module reads cases only from its projection `merchant_after_sales_cases` and answers only through `decideAfterSalesCase`.
- A refund needs the order's `order_payments` capture (written only by `POST /payments/checkout`, in the transaction that creates the PAID order) and never exceeds it: opening a request checks it against the capture minus everything approved for the order (409), and `RefundExecutor` re-checks, before the gateway call, that the case is `REFUNDING`, the amount equals the approved amount, the payment reference matches and the capture covers it, else `FAILED` without a call. No capture or a 0.00 total means no automatic refund on cancel. Compare amounts with `Money`, never `BigDecimal.equals`.
- One more customer request per order, only after the first was withdrawn (`attempt_no` 2, same window from the completion), checked under the order's row lock with `UNIQUE (order_id, kind, attempt_no)` behind it; a rejected, timed-out or refunded request never allows another, so at most one case of an order reaches `REFUNDING`.
- Customer commands and the arbitration take an `Idempotency-Key` through `IdempotentRequests` (in the caller's transaction; the record is bound to the case the request opened or changed, so a replay answers with that case). The open scope is the order, so a second request needs a new key. Merchant answers and the refund retry take none (idempotent by state). Arbitrations and refund retries that changed something are audited (`after_sales_case.arbitrate`, `refund.retry`).
- Deadlines are hard for the customer's and the merchant's answers (guard `beforeDeadline`, 409 `AFTER_SALES_DEADLINE_PASSED`, even before the poller fired) and soft for withdrawing and arbitrating. The merchant timeout runs for every restaurant, staffed or not. `AfterSalesPoller` is a `SmartLifecycle` with its own thread (`after-sales-poller`; deadlines, then refunds, within `tick-budget`), never a `TaskScheduler` bean, and follows `app.kafka.enabled` unless `app.after-sales.poller.enabled` is set. Gateway calls run outside any transaction on at most `refund-gateway.max-threads` threads, `refund-gateway.timeout` must stay below `refund.claim-timeout` (startup check), and a `RefundGateway` must be idempotent per `gateway_key` (one per case).
- Events: `after-sales-events` (key the order id, aggregate `AFTER_SALES_CASE`, constants in `FulfilmentEvents`), consumed by `onlineorder-merchant-aftersales` (the merchant projection; drops `ORDER_CANCELLED` cases, skips stale events by sequence and never moves a row back to an older case version) and `onlineorder-aftersales-notification` (one notification per order, event type and case: `dedup_scope` `'case:' || case_id`; skips stale events by sequence, moving its position only for events that notify, `OrderNotificationService.notifiesOf`). Merchant commands are 404 until the projection has the case.
- Tests: `AfterSalesStateMachineTests`, `RefundStateMachineTests`; `AfterSalesApi*`, `AfterSalesDeadline*`, `RefundExecutor*`, `CancellationRefund*`, `AfterSalesAdmin*IntegrationTests`, `MerchantAfterSales*IntegrationTests`, `AfterSalesNotification*IntegrationTests`, `AfterSalesSchema*Tests` (each on H2 and PostgreSQL), `AfterSalesDemoProfileTests`, and end to end `AfterSalesFlowContainerTests`.

## Event Contracts and Ordering

README "Event contracts"; operations in `docs/operations.md` section 8. Migrations `V9` (aggregate sequences), `V9_1` (head-of-line index, Java) and `V9_2` (consumer positions) belong here.

- Every event has a JSON Schema (draft 2020-12) in `backend/src/main/resources/event-schemas/<topic>/<event_type>/v<N>.json`, listed in `catalog.json` with its compatibility (`INITIAL`, `BACKWARD`, `BREAKING`) and checksummed in `SHA256SUMS`. A published schema file never changes and is never deleted. To change an event, add `v<N+1>.json` (`BACKWARD`: only optional properties, checked both ways against the earlier versions' samples; anything else, a relaxed constraint included, is `BREAKING`: consumers that accept N and N-1 ship first and N-1 stays listed), list it, add its `SHA256SUMS` line and samples under `backend/src/test/resources/event-samples/<topic>/<event_type>/v<N+1>/`, then raise the producer's version constant. A new event type needs the same plus a constant in `FulfilmentEvents`. `EventSchemaContractTests` and the CI step "Published event schemas are append-only" enforce this.
- Payload records live in `messaging` (`OrderEventPayload`, `OrderEventItem`, ...) and never reuse REST DTOs; the JSON is the application ObjectMapper's (snake_case, nulls omitted). Money amounts carry an optional ISO 4217 `currency` next to them.
- Producers build an `EventEnvelope` without a sequence; `enqueueEvent` assigns the aggregate's next sequence (`outbox_aggregate_sequences`, row-locked until commit; never derive it from the outbox, whose rows are deleted) and validates the event, throwing `EventSchemaViolationException` (the transaction rolls back). Consumers read every record with `EventContracts.read(topic, payload, type)`, never `objectMapper.readValue`: a violation is not retryable and goes straight to the DLT.
- The publisher claims only the head of each aggregate (no earlier `PENDING`/`PROCESSING`/`FAILED` event of the same `aggregate_type`/`aggregate_id`) and claims again in the same run while a published head has a `PENDING` successor, so a `FAILED` event blocks its aggregate until it is retried or, as a last resort, abandoned (`POST /admin/outbox/events/{id}/abandon`, audited; `ABANDONED` is outside the claim's statuses and is never published); never mark held-back events `PUBLISHED` by hand. `FAILED` rows from before V9 have no sequence and block nothing. The sequence allocator is chosen by database product (`OutboxSequenceAllocatorConfig`), not by profile. Outbox rows inserted without `enqueueEvent` (raw test inserts) have no sequence and are published unordered. The ordering tests are `OutboxAggregateOrderingPostgresTests` (two publishers, induced failures) and `OutboxAggregateSequenceMigration*Tests`.
- A consumer that keeps per-aggregate state calls `EventSequenceTracker.advance(consumerName, event)` after its `processed_events` mark and only for event types it applies; on false the event is stale and only facts no later event carries are merged (the merchant inbox still records a late `order.paid`'s payment time). Its tests: `StaleEventPostgresIntegrationTests`.

## Observability and Delivery

Details in `docs/operations.md`.

- Tracing is Micrometer Tracing + OpenTelemetry with W3C `traceparent`. `X-Trace-Id` and error-body `trace_id` are the OpenTelemetry trace id. Spans are exported only with `TRACING_EXPORT_ENABLED=true`; tests never export, and Spring Boot tests turn propagation off (`management.tracing.export.enabled=false`) unless the class has `@AutoConfigureTracing` (needed to assert trace ids). Metrics export (the Prometheus endpoint) in tests needs `@AutoConfigureMetrics`.
- Insert outbox rows through `OutboxEventRepository.save(...)`: `OutboxTraceContextCallback` stores the current traceparent in `outbox_events.trace_parent` (V6) and the publisher continues that trace. A hand-written `INSERT` loses the trace. Kafka observation is enabled for every `KafkaTemplate` and listener container factory bean by `TracingConfig`, including new ones.
- `MANAGEMENT_SERVER_PORT=8081` (Kubernetes ConfigMap, the `app` profile of `deploy/observability`; not baked into the image) serves the actuator on its own port without a session; unset, the actuator stays on 8080 behind `ROLE_ADMIN` (local runs, tests, plain `docker run`). Point k6 at a split management port with `MANAGEMENT_URL`.
- CI's `backend-test` job runs `./gradlew test jacocoTestReport jacocoTestCoverageVerification spotbugsMain -x buildFrontend -x syncFrontendBuild --no-daemon` (coverage minimums in `backend/build.gradle`; SpotBugs baseline in `backend/config/spotbugs/exclude.xml`). Its `config-lint` job validates `deploy/` and the workflows. `release.yml` runs CI, a Trivy gate (dated exceptions in `.trivyignore`; security patch overrides at the top of `backend/build.gradle`), an SBOM, the GHCR push and kustomize deploys (staging only with a `KUBE_CONFIG` secret; production through the protected environment).
- Validate observability and Kubernetes changes with promtool (`check rules`, `test rules alerts.test.yml`, `check config`), `docker compose -f deploy/observability/docker-compose.yml --profile app config`, and `kubectl kustomize deploy/k8s/overlays/<env>` + kubeconform; the exact commands are in `docs/operations.md` and the `config-lint` job.
