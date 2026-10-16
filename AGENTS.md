# AGENTS.md

## Project Shape

- Full-stack food ordering app: React frontend in `frontend/`, Spring Boot backend in `backend/`.
- Backend uses Java 17, Gradle wrapper 8.14.3, PostgreSQL, Redis, Kafka, Spring Session, Actuator, Flyway, and Testcontainers.
- Frontend is Create React App with React scripts 4 and Ant Design. CI and Docker use Node 20.

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
npm ci --legacy-peer-deps
npm start
```

The frontend dev server runs on `http://localhost:3000` and proxies API calls to `http://localhost:8080`.

```sh
cd frontend
npm run build
```

React scripts are wrapped with `--openssl-legacy-provider` in `package.json`; use the npm scripts instead of invoking `react-scripts` directly.

### Packaging and embedded frontend assets

```sh
cd frontend
npm ci --legacy-peer-deps
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
  - `npm ci --legacy-peer-deps` and `npm run build` in `frontend/`
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

## Edit Guidelines

- Do not hand-edit generated frontend build output under `frontend/build/` or copied backend static output under `backend/src/main/resources/public/`; regenerate it through the frontend build/deploy commands when intentionally updating embedded assets.
- Keep schema changes in versioned Flyway migrations under `backend/src/main/resources/db/migration`. Put non-destructive local/demo seed changes in `backend/src/main/resources/db/demo` when they are not required production schema.
- DDL that H2 cannot run (partial indexes, `CREATE INDEX CONCURRENTLY`) goes in a versioned Java migration under `backend/src/main/java/db/migration` that only records its version on other databases; `V3__Add_outbox_unpublished_index` is the pattern. The `h2` profile runs the same migration list.
- Treat checkout, cart mutation, order status changes, outbox publishing, idempotency, and DLT replay as correctness-sensitive paths; update focused tests when changing them.

## Observability and Delivery

Details in `docs/operations.md`.

- Tracing is Micrometer Tracing + OpenTelemetry with W3C `traceparent`. `X-Trace-Id` and error-body `trace_id` are the OpenTelemetry trace id. Spans are exported only with `TRACING_EXPORT_ENABLED=true`; tests never export, and Spring Boot tests turn propagation off unless the class has `@AutoConfigureObservability` (needed to assert trace ids).
- Insert outbox rows through `OutboxEventRepository.save(...)`: `OutboxTraceContextCallback` stores the current traceparent in `outbox_events.trace_parent` (V6) and the publisher continues that trace. A hand-written `INSERT` loses the trace. Kafka observation is enabled for every `KafkaTemplate` and listener container factory bean by `TracingConfig`, including new ones.
- `MANAGEMENT_SERVER_PORT=8081` (Kubernetes ConfigMap, the `app` profile of `deploy/observability`; not baked into the image) serves the actuator on its own port without a session; unset, the actuator stays on 8080 behind `ROLE_ADMIN` (local runs, tests, plain `docker run`). Point k6 at a split management port with `MANAGEMENT_URL`.
- CI's `backend-test` job runs `./gradlew test jacocoTestReport jacocoTestCoverageVerification spotbugsMain -x buildFrontend -x syncFrontendBuild --no-daemon` (coverage minimums in `backend/build.gradle`; SpotBugs baseline in `backend/config/spotbugs/exclude.xml`). Its `config-lint` job validates `deploy/` and the workflows. `release.yml` runs CI, a Trivy gate (dated exceptions in `.trivyignore`; security patch overrides at the top of `backend/build.gradle`), an SBOM, the GHCR push and kustomize deploys (staging only with a `KUBE_CONFIG` secret; production through the protected environment).
- Validate observability and Kubernetes changes with promtool (`check rules`, `test rules alerts.test.yml`, `check config`), `docker compose -f deploy/observability/docker-compose.yml --profile app config`, and `kubectl kustomize deploy/k8s/overlays/<env>` + kubeconform; the exact commands are in `docs/operations.md` and the `config-lint` job.
