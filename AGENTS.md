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
- Prefer the root `Dockerfile` for a full source build. `backend/Dockerfile` expects `backend/build/libs/OnlineOrder-0.0.1-SNAPSHOT.jar` to already exist.

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
- In the frontend, a retry of the same checkout, cancel, or status change must reuse its `Idempotency-Key` (see `useCheckout`, `useOrders`, `useAdminTools`), and responses for superseded requests must be dropped through `useLatestRequest`; `src/App.test.jsx` covers both.
