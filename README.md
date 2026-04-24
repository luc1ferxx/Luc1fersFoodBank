# OnlineOrder

OnlineOrder is a full-stack food ordering system built as an **event-driven Spring Boot monolith**. It is intentionally not a microservice architecture: the backend keeps transactional order logic in one service boundary, PostgreSQL is the source of truth, Redis provides shared runtime state, and Kafka is used to decouple asynchronous order processing.

The project is designed to demonstrate **business correctness before scale-out**:

- transactional checkout
- cart-level concurrency control
- request idempotency
- transactional outbox publishing
- consumer-side deduplication
- dead-letter persistence and replay
- session-based security and operational observability

---

## System Highlights

- **Single transactional backend** for cart, checkout, order lifecycle, and admin actions
- **PostgreSQL row locking** to serialize writes against the same cart
- **Idempotent checkout** backed by a durable `idempotency_requests` table
- **Transactional outbox** so order events are persisted in the same database transaction as the order itself
- **Kafka consumer deduplication** via `processed_events`
- **Dead-letter workflow** with persistent storage and admin replay endpoint
- **Redis-backed shared state** for Spring Session, cache, and rate limiting
- **Production-shaped user lifecycle controls** with account status, login lockout, and last-login audit fields
- **Operational visibility** through Actuator, Micrometer, Prometheus, and `X-Trace-Id`
- **Automated verification** with unit tests, Testcontainers integration tests, and GitHub Actions

---

## Technology Stack

### Frontend
- React
- Ant Design
- Browser session cookies

### Backend
- Java 17
- Spring Boot
- Spring MVC
- Spring Security
- Spring Data JDBC
- Gradle

### Data and Messaging
- PostgreSQL for customers, carts, orders, idempotency records, outbox events, processed events, notifications, and dead-letter records
- Redis for session storage, cache, and distributed rate-limit counters
- Kafka for asynchronous order event delivery

### Observability and Delivery
- Spring Boot Actuator
- Micrometer + Prometheus endpoint
- structured trace propagation with `X-Trace-Id`
- Docker Compose for local infrastructure
- Testcontainers for integration testing
- GitHub Actions for CI

---

## Architecture Overview

```text
React SPA
  -> Spring MVC controllers
  -> service layer with transactions, locking, idempotency, and state validation
  -> Spring Data JDBC repositories
  -> PostgreSQL

Checkout transaction
  -> lock cart row
  -> validate cart and checkout state
  -> create order and order history items
  -> persist idempotency result
  -> write outbox event in the same DB transaction
  -> clear cart

Async processing
  -> scheduled outbox publisher claims pending events
  -> Kafka publishes order event
  -> consumer deduplicates with processed_events
  -> notification record is created
  -> failures go to DLT storage and can be replayed by admin
```

### Architectural Positioning

This repository is best described as an **industrialized monolith** rather than a distributed system. The design goal is to keep core order correctness simple and explicit before introducing service decomposition.

That means:
- one write authority for checkout
- database-backed correctness guarantees
- asynchronous integration via events only after commit
- operational recovery paths for failed asynchronous work

---

## Core Business Flow

1. User authenticates with a server-side session.
2. User browses restaurants and menu items.
3. User adds or updates cart items.
4. Checkout is submitted through `POST /payments/checkout` or `POST /cart/checkout` with an idempotency key.
5. Backend creates the order once, stores checkout idempotency state, clears the cart, and writes an outbox event in the same transaction.
6. Outbox publisher sends the event to Kafka after commit.
7. Kafka consumer processes the event once, persists deduplication state, and creates user-facing notifications.
8. Admin can move the order through the status state machine.
9. User can cancel an owned order when the current state allows it.

---

## Checkout Correctness Guarantees

### Concurrency Control
- Cart mutation uses PostgreSQL `SELECT ... FOR UPDATE` to serialize writes for the same customer cart.
- Add-to-cart quantity uses PostgreSQL upsert on `(cart_id, menu_item_id)`.
- Concurrent checkout attempts against the same cart are serialized by the cart row lock, so only one order can consume the cart contents.

### Idempotency
- Checkout idempotency is persisted in `idempotency_requests` with a unique `(customer_id, scope, idempotency_key)` constraint.
- Checkout reuse is hardened with a **server-derived fingerprint** that includes checkout scope plus the cart snapshot.
- The same idempotency key only replays the same logical checkout request.
- A successful retry still reuses the stored order even after the cart has already been cleared.
- Reusing the same idempotency key with changed cart contents is rejected as a conflict.

### Event Delivery
- Outbox publishing uses database claiming with `FOR UPDATE SKIP LOCKED`.
- Kafka consumers use `processed_events` with unique `(consumer_name, dedup_key)` to make event handling idempotent.
- Failed event handling is persisted to dead-letter storage and can be replayed by an admin endpoint.

### Multi-User Session and Abuse Controls
- Authentication stays session-based with Redis-backed Spring Session instead of pushing complexity into JWT refresh / revoke flows.
- Customer records now carry `account_status`, `failed_login_attempts`, `locked_until`, `last_login_at`, `created_at`, and `updated_at` so the system can enforce account lifecycle policy inside the same transactional data model.
- Login failures increment a per-user counter, lock the account after repeated bad credentials, and successful login clears the lock state and records the latest login timestamp.
- User lookup and authority lookup are both case-insensitive so mixed-case email logins still resolve the correct principal and roles.

---

## Operational Features

- **Sessions:** Redis-backed Spring Session
- **Rate limiting:** anonymous auth endpoints are limited by remote IP, while authenticated checkout/admin flows are limited by user identity for fairer multi-user protection
- **Rate-limit resilience:** Redis counter failures fail open, emit a warning, and increment a metric instead of taking down login or checkout
- **Metrics:** Actuator + Micrometer + Prometheus endpoint
- **Traceability:** `X-Trace-Id` is returned and propagated into logs
- **Recovery:** DLT persistence and replay API
- **Health surface:** health/info/metrics/prometheus endpoints

---

## API Surface

### Customer-facing
- `GET /restaurants` — list restaurants
- `GET /cart` — get current cart
- `POST /cart` — add one menu item with body `{ "menu_id": <id> }`
- `PUT /cart/items/{orderItemId}` — update item quantity
- `DELETE /cart/items/{orderItemId}` — remove cart item
- `POST /cart/checkout` — checkout with cart API and idempotency key
- `POST /payments/checkout` — checkout with payment-style API and idempotency key
- `GET /orders` — list current user's orders
- `POST /orders/{orderId}/cancel` — cancel an owned order if allowed
- `GET /notifications` — list current user's notifications

### Admin-facing
- `PATCH /orders/{orderId}/status` — order state transition
- `POST /dead-letters/{deadLetterEventId}/replay` — replay dead-lettered event

### Operational
- `GET /actuator/health`
- `GET /actuator/info`
- `GET /actuator/metrics`
- `GET /actuator/prometheus`

---

## Local Development

### Start infrastructure
```powershell
cd backend
docker compose up -d
```

### Run backend
```powershell
cd backend
gradlew.bat bootRun
```

### Run frontend dev server
```powershell
cd frontend
npm install
npm start
```

### Open the application
```text
http://localhost:8080
```

### Default local infrastructure
- PostgreSQL: `localhost:5433`, database `onlineorder`, user `postgres`, password `secret`
- Redis: `localhost:6379`
- Kafka: `localhost:9092`

### Demo account
- Email: `demo@laifood.com`
- Password: `demo123`
- Roles: `ROLE_USER`, `ROLE_ADMIN`

---

## Development Commands

### Run backend tests
```powershell
cd backend
gradlew.bat test -x buildFrontend -x syncFrontendBuild --no-daemon
```

### Build backend with embedded frontend assets
```powershell
cd backend
gradlew.bat processResources
```

---

## Testing Strategy

The repository uses multiple layers of verification:

- **unit tests** for service behavior and edge cases
- **integration tests** with Testcontainers for PostgreSQL / Redis / Kafka flows
- **checkout correctness tests** for idempotency reuse and concurrent checkout behavior
- **authentication lifecycle tests** for signup defaults, login lockout, case-insensitive identity lookup, and successful-login reset behavior
- **security filter tests** for user-scoped rate limiting, IP-scoped anonymous throttling, and Redis fail-open behavior
- **CI automation** through GitHub Actions

The most important tests are the ones around:
- checkout creation
- idempotent retry behavior
- outbox publishing
- consumer deduplication
- DLT replay behavior
- cart concurrency and checkout serialization
- account lock / unlock behavior
- authenticated-vs-anonymous rate-limit scoping

---

## Current Boundaries

This project is intentionally strong on correctness patterns but still limited in product scope.

- schema initialization still uses `database-init.sql`, not Flyway or Liquibase
- payment is simulated; there is no real payment gateway callback
- there is no inventory table yet, so stock reservation / oversell prevention is future work
- H2 is only a lightweight fallback profile; production-style runtime state is designed around PostgreSQL + Redis + Kafka
- the architecture is still a monolith by design; service decomposition is intentionally deferred

---

## Why This Project Is Interesting

OnlineOrder is not just a CRUD food app. It is a compact backend system that demonstrates how to make a transactional business flow more production-shaped without immediately jumping to microservices.

The main theme of the project is:

> **make checkout correct first, then make it distributed later**

That is why the repository focuses on locking, idempotency, outbox, deduplication, replay, and observability as first-class concerns.
