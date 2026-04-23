# OnlineOrder

OnlineOrder is a full-stack food ordering demo. It is currently an event-driven Spring Boot monolith, not a microservice system. The backend owns the transactional order flow, PostgreSQL is the source of truth, Redis is the only cache/session/limiter backing store, and Kafka is used for asynchronous order events.

## Tech Stack

- Frontend: React, Ant Design, browser session cookies.
- Backend: Java 17, Spring Boot, Spring MVC, Spring Security, Spring Data JDBC, Gradle.
- Database: PostgreSQL for customers, carts, orders, idempotency records, outbox events, processed events, notifications, and dead-letter records.
- Cache and shared state: Redis for Spring Cache, Spring Session, and distributed rate-limit counters. There is no local/simple cache manager.
- Messaging: Kafka for order events, with outbox publishing, consumer idempotency, DLT storage, and admin replay.
- Observability: Spring Boot Actuator, Micrometer, Prometheus endpoint, `X-Trace-Id`, and trace id in logs.
- Delivery and testing: Docker Compose for local infrastructure, Testcontainers integration tests, GitHub Actions CI, and Docker image release workflow.

## Architecture

```text
React SPA
  -> Spring MVC controllers
  -> services with transactions, idempotency, and state validation
  -> Spring Data JDBC repositories
  -> PostgreSQL

Checkout transaction
  -> lock cart row
  -> create order and history items
  -> persist idempotency result
  -> write outbox event in the same DB transaction
  -> scheduled outbox publisher sends event to Kafka
  -> Kafka consumer deduplicates with processed_events
  -> notification record is created
  -> failed messages go to DLT and can be replayed
```

## Main Business Flow

1. User signs up or logs in with a server-side session stored in Redis.
2. User browses restaurants and menu items.
3. User adds items to cart. Cart writes are transactional and guarded by database row locks.
4. User checks out through `POST /payments/checkout` or `POST /cart/checkout` with an idempotency key.
5. Backend creates the order once, stores the idempotency result, clears the cart, and writes an outbox event.
6. Outbox publisher sends the order event to Kafka after the database transaction is committed.
7. Consumer handles the event once per consumer name and event id, then creates user-facing notifications.
8. Admin can move the order through the state machine with `PATCH /orders/{id}/status`.
9. User can cancel an owned order with `POST /orders/{id}/cancel` when the state machine allows it.

## Concurrency And Idempotency

- Cart mutation uses PostgreSQL `SELECT ... FOR UPDATE` to serialize writes for the same customer cart.
- Add-to-cart quantity uses PostgreSQL upsert on `(cart_id, menu_item_id)`.
- Checkout idempotency uses `idempotency_requests` with a unique `(customer_id, scope, idempotency_key)` constraint.
- Outbox publishing uses database claiming with `FOR UPDATE SKIP LOCKED`.
- Kafka consumers use `processed_events` with unique `(consumer_name, dedup_key)` to make event handling idempotent.
- Rate limiting uses Redis atomic counters, so it works across multiple backend instances.

## Local Run

Start infrastructure:

```powershell
cd backend
docker compose up -d
```

Run backend:

```powershell
cd backend
gradlew.bat bootRun
```

Open the app:

```text
http://localhost:8080
```

Default infrastructure:

- PostgreSQL: `localhost:5433`, database `onlineorder`, user `postgres`, password `secret`.
- Redis: `localhost:6379`.
- Kafka: `localhost:9092`.

Demo account:

- Email: `demo@laifood.com`
- Password: `demo123`
- Roles: `ROLE_USER`, `ROLE_ADMIN`

## Useful Endpoints

- `GET /restaurants`: list restaurants.
- `POST /cart/{menuItemId}`: add one menu item to cart.
- `POST /payments/checkout`: checkout with payment-style API and idempotency key.
- `GET /orders`: list current user's orders.
- `PATCH /orders/{orderId}/status`: admin order state transition.
- `POST /orders/{orderId}/cancel`: customer cancel.
- `GET /notifications`: current user's notifications.
- `POST /dead-letters/{deadLetterEventId}/replay`: admin DLT replay.
- `GET /actuator/health`, `GET /actuator/info`, `GET /actuator/metrics`, `GET /actuator/prometheus`: operational endpoints.

## Development Commands

Run backend tests:

```powershell
cd backend
gradlew.bat test -x buildFrontend -x syncFrontendBuild --no-daemon
```

Run React dev server:

```powershell
cd frontend
npm install
npm start
```

Build backend with the current frontend assets:

```powershell
cd backend
gradlew.bat processResources
```

## Current Boundaries

- The project still uses `database-init.sql` for schema initialization, not Flyway or Liquibase.
- Payment is simulated; there is no real payment provider callback yet.
- There is no real inventory table yet, so full stock-level oversell prevention is still a future feature.
- H2 profile is only a lightweight database fallback. Cache-related runtime state is still designed to use Redis.
