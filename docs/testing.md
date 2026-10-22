# Testing and Verification

Run the normal backend verification path:

```sh
(cd backend && ./gradlew test -x buildFrontend -x syncFrontendBuild --no-daemon)
```

The test classes run in parallel JVMs: half the machine's CPUs, at most 4 (`maxParallelForks` in `backend/build.gradle`).
Each JVM starts its own Testcontainers, so a Docker host with little memory may want fewer: `-PtestForks=2`, or
`-PtestForks=1` to run the classes one after another, which is also the way to tell a real failure from one caused by
the load of the other forks.

Type-check, lint, test, and build the React app:

```sh
(cd frontend && npm run typecheck && npm run lint && npm test && npm run build)
```

`npm run typecheck` runs `tsc --noEmit` (strict TypeScript). `npm test` runs Vitest with jsdom and React Testing Library against an in-memory fake API (login, cart and the clear-cart dialog, checkout success and failure, Idempotency-Key reuse on retry, session expiry, stale responses, rate-limited requests (the `Retry-After` copy, errors on the menu card used, a refused checkout retried with its key), polling, order status history, role-based workspaces, the merchant, rider, and admin consoles, and the after-sales screens of all three, with the customer's and the admin's Idempotency-Key reuse across workspace switches). `npm run build` writes `frontend/build` and fails if `index.html` contains an inline script, which the backend CSP would block.

Build the backend jar with embedded frontend assets:

```sh
(cd backend && ./gradlew bootJar --no-daemon)
```

The test suite covers:

- checkout idempotency, rollback, and concurrent checkout behavior
- order status transition rules and rollback behavior
- outbox publish success, retryable failure, max-attempt failure, and admin retry
- Kafka consumer deduplication and notification idempotency
- dead-letter replay behavior
- two-level catalog cache: L1/L2 hits, single-flight loading, TTL jitter, typed Redis round trips, pub/sub invalidation between instances, eviction only after commit, HTTP `ETag`/`304`, fallback to the database when Redis refuses connections, and latency bounds when Redis hangs (paused container)
- authentication, login lockout, session handling, and rate limiting: every write mapping covered by a bucket and
  taking its ids as `\d+` (`RateLimitedRouteMappingTests`), each bucket's `Retry-After` (`RateLimitingFilterTests`),
  and on a real Redis the capacity, the refusal with `Retry-After`, the refill, separate buckets per login, address
  and route, and the key's expiry (`RateLimitingFilterRedisIntegrationTests`)
- API contract and operational metrics behavior
- order fulfilment: merchant scoping and authz, accept-versus-timeout races, concurrent deadline pollers and rider
  assignment, duplicate and out-of-order (replayed) event deliveries (`FulfilmentPostgresIntegrationTests`), and end
  to end on PostgreSQL, Redis and Kafka: pay -> accept -> deliver -> complete with and without the merchant's
  `PREPARING`, once through the rider API (`FulfilmentFlowContainerTests`), and the accept timeout at a staffed and an
  unstaffed restaurant and the payment timeout (`FulfilmentTimeoutContainerTests`)
- event contracts and order: every published schema unchanged, valid and listed, every version accepting its samples
  and `BACKWARD` versions compatible both ways with the versions before (`EventSchemaContractTests`, with fixture sets
  that break each rule); producer and consumer validation, including dead-lettering on the first attempt end to end
  (`ContainerizedOrderFlowTests`); two publishers with induced failures never publishing an aggregate's events out of
  order, a `FAILED` head blocking its aggregate until retried or abandoned, a backlog drained in one run, and
  sequences under concurrent writers (`OutboxAggregateOrderingPostgresTests`, on H2 `OutboxSequenceAllocatorH2Tests`); the V9 backlog numbering and head index (`OutboxAggregateSequenceMigration*Tests`);
  consumers skipping stale events (`StaleEventPostgresIntegrationTests`)
- staff accounts: provisioning, `GET /me`, the role matrix, deactivation on the next request, dispatch to active
  on-duty riders only, and a rider never reaching another rider's delivery (`StaffAccountsPostgresIntegrationTests`)
- read replicas, on a real primary and streaming replica (official image, `pg_basebackup`): which transactions read
  the replica, read-your-writes after a session's own write, lagging, stopped and hung replicas falling back to the
  primary (`ReadReplicaRoutingIntegrationTests`), and the whole order flow with routing on
  (`FulfilmentReplicaRoutingContainerTests`)
- 64-bit keys and the audit trails: an order with ids past 2^31 through checkout and fulfilment on PostgreSQL and H2
  (`LargeId*IntegrationTests`), upgrading a populated V6 database (`KeyWideningMigration*Tests`), the online key swap
  and history repair runbook (`SchemaRolloutPostgresTests`), order status history and its actors
  (`OrderLifecycleCommandsTests`, `FulfilmentPostgresIntegrationTests`), the audit log of admin and staff actions
  (`AuditLogIntegrationTests`, `StaffAuditIntegrationTests`), and `DELETE /cart` (`CartClearIntegrationTests`)
- the data tier: upgrading a populated V8 database to monthly partitions and numbered orders on PostgreSQL and H2
  (`ScaleMigration*Tests`), partition creation, row moves out of the default partition (also with a writer racing the
  move), the lock order against a status change, retention drops, the maintenance lock and metrics
  (`PartitionMaintenancePostgresIntegrationTests`), deduplication across partitions under concurrency, including two
  marks of one key racing into different months (`ProcessedEventDedupAcrossPartitionsPostgresTests`), the Snowflake generator's uniqueness, monotonicity,
  clock steps and worker-id bounds (`SnowflakeIdGeneratorTests`), leased worker ids with takeovers and clock skew
  (`WorkerIdLeasePostgresTests`), and order numbers through checkout and notifications (`OrderNumber*IntegrationTests`)
- money: `Money` arithmetic, HALF_EVEN rounding and currency rules (`MoneyTests`), upgrading a populated V8 database
  to currencies and `NUMERIC(12,2)`, including a price that does not fit (`MoneyCurrencyMigration*Tests`), a euro
  checkout with exact totals, its event and the database's single-currency carts on PostgreSQL and H2
  (`CurrencyCheckout*IntegrationTests`), the `currency` fields of the API (`ApiContractIntegrationTests`), and the
  frontend's decoding and formatting (`src/lib/format.test.ts`, `src/api/client.test.ts`, `src/App.test.tsx`)
- state machines: the declaration checks of the generic component (`StateMachineTests`), and for the order, delivery,
  after-sales and refund machines the decision of every trigger from every state, the allowed actors, the guards and
  the Mermaid drawing (`OrderStateMachineTests`, `DeliveryStateMachineTests`, `AfterSalesStateMachineTests`,
  `RefundStateMachineTests`)
- after-sales on PostgreSQL and H2: requests, eligibility, the second request and idempotent replays
  (`AfterSalesApi*IntegrationTests`), each timeout's outcome, answers racing their timeout, late answers refused and
  failed firings backing off (`AfterSalesDeadline*IntegrationTests`), refund retries, give-ups, reclaimed leases, a
  stalled executor against its reclaimer and claims that skip locked rows (`RefundExecutor*IntegrationTests`),
  automatic refunds of cancelled orders (`CancellationRefund*IntegrationTests`), arbitration and refund retries
  (`AfterSalesAdmin*IntegrationTests`), the merchant's inbox and answers
  (`MerchantAfterSales*IntegrationTests`); races run every serial order of two commands by queueing them on a
  held row lock (`PostgresRowLocks`); the `demo` profile without Kafka (`AfterSalesDemoProfileTests`); and end to end
  on PostgreSQL, Redis and Kafka through the three roles' APIs, each refund failing once first: approve, counter-offer,
  rejection and partial arbitration, the merchant timeout, a cancelled paid order, and a second request after a
  withdrawal (`AfterSalesFlowContainerTests`)

Some integration tests use Testcontainers and require Docker. Tests annotated with `@Testcontainers(disabledWithoutDocker = true)` may skip when Docker is unavailable.

The backend runs on Spring Boot 4.1; [docs/spring-boot-4-migration.md](spring-boot-4-migration.md) lists what changed from Spring Boot 3 (packages, Jackson 3, Spring Security 7, test annotations, configuration properties, the versioned session namespace, the H2 pin) for porting older code.


## Useful Commands

| Task | Command |
| --- | --- |
| Start infrastructure | `cd backend && docker compose up -d` |
| Run backend tests | `cd backend && ./gradlew test -x buildFrontend -x syncFrontendBuild --no-daemon` |
| Run backend with demo seed | `cd backend && FLYWAY_LOCATIONS=classpath:db/migration,classpath:db/demo ./gradlew bootRun` |
| Run backend with H2 profile | `cd backend && SPRING_PROFILES_ACTIVE=h2 ./gradlew bootRun` |
| Run the after-sales demo on H2 | `cd backend && SPRING_PROFILES_ACTIVE=h2,demo ./gradlew bootRun` |
| Start frontend dev server | `cd frontend && npm start` |
| Type-check, lint and test frontend | `cd frontend && npm run typecheck && npm run lint && npm test` |
| Build frontend | `cd frontend && npm run build` |
| Copy frontend build into backend | `cd frontend && npm run deploy:backend` |
| Build executable jar | `cd backend && ./gradlew bootJar --no-daemon` |
| Run load test | `k6 run load-tests/order-flow.js`, against a backend started with `SECURITY_RATE_LIMIT_ENABLED=false` (the `signup` and `login` buckets per IP and `payment-checkout` per user refuse it otherwise) |
| Backend CI gates | `cd backend && ./gradlew test jacocoTestReport jacocoTestCoverageVerification spotbugsMain -x buildFrontend -x syncFrontendBuild --no-daemon` |
| Observability stack + app | `ONLINEORDER_SCRAPE_TARGETS=./prometheus/targets/compose docker compose -f deploy/observability/docker-compose.yml --profile app up -d --build` (Grafana on 3001) |
| Build the image | `docker build -t onlineorder:local .` |
| Render Kubernetes manifests | `kubectl kustomize deploy/k8s/overlays/staging` |
