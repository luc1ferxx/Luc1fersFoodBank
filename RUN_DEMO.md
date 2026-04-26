# Run Demo

## Backend Demo

1. Start PostgreSQL, Redis, and Kafka from [docker-compose.yml](/c:/Users/Jxx/Desktop/OnlineOrder/backend/docker-compose.yml):
   `cd backend`
   `docker compose up -d`
2. Open `backend` as a Gradle project in IntelliJ.
3. Use JDK 17 or newer for the project SDK.
4. Run `com.laioffer.onlineorder.OnlineOrderApplication`.
   For a disposable local/demo database, set `INIT_DB=always` before startup so `database-init.sql` initializes demo data. That script drops and recreates tables, so leave the default `INIT_DB=never` for any database whose data must be preserved.
5. Open `http://localhost:8080`.

The backend now defaults to:

- PostgreSQL on `localhost:5433`, database `onlineorder`
- Redis on `localhost:6379` for shared sessions, cache, and distributed rate-limit counters
- Kafka on `localhost:9092` for asynchronous order events

Operational endpoints and jobs:

- `POST /dead-letters/{id}/replay` replays one stored dead-letter event back to its original Kafka topic
- `PATCH /orders/{id}/status` updates an order status through the state machine; the demo user can call it because it has `ROLE_ADMIN`
- `POST /orders/{id}/cancel` cancels one of the current user's orders when the state machine allows it
- `GET /actuator/health`, `GET /actuator/info`, `GET /actuator/metrics/**`, and `GET /actuator/prometheus` are enabled; only `health` and `info` are anonymous
- Every HTTP response includes `X-Trace-Id` for request correlation in logs
- Scheduled cleanup removes old `published outbox`, `processed_events`, `idempotency_requests`, and replayed dead letters
- Cleanup cadence and retention are configurable with `CLEANUP_*` environment variables

Default PostgreSQL credentials:

- Username: `postgres`
- Password: `secret`

Demo account:

- Email: `demo@laifood.com`
- Password: `demo123`
- Roles: `ROLE_USER`, `ROLE_ADMIN`

## H2 Fallback

If you want to run without PostgreSQL and Kafka, start the backend with the `h2` profile instead. Redis is still the configured cache backend; the project no longer uses Spring's local/simple cache manager.

```powershell
$env:SPRING_PROFILES_ACTIVE="h2"
cd c:\Users\Jxx\Desktop\OnlineOrder\backend
gradlew.bat bootRun
```

That enables the in-memory H2 datasource and `/h2-console`; keep Redis running if you exercise cache-backed paths.

## If You Change The React Frontend

The Spring app now rebuilds and stages the frontend automatically during backend resource processing, so `gradlew.bat bootRun` picks up the current React code under `frontend/`.

If you want a manual production rebuild:

```powershell
cd c:\Users\Jxx\Desktop\OnlineOrder\backend
gradlew.bat processResources
```

Then rerun the Spring Boot app and refresh `http://localhost:8080`.

## Optional Frontend Dev Server

If you want hot reload while editing React:

```powershell
cd c:\Users\Jxx\Desktop\OnlineOrder\frontend
npm install
npm start
```

Then open `http://localhost:3000`.
