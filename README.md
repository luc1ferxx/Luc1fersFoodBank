# Luc1fer's Food Bank

<p align="center">
  <strong>A production-shaped food ordering platform built around the hardest part of ordering software: making checkout correct.</strong>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Java-17-b07219?style=for-the-badge" alt="Java 17" />
  <img src="https://img.shields.io/badge/Spring%20Boot-4.2-6db33f?style=for-the-badge" alt="Spring Boot 4.2" />
  <img src="https://img.shields.io/badge/React-19-61dafb?style=for-the-badge" alt="React 19" />
  <img src="https://img.shields.io/badge/PostgreSQL-15-4169e1?style=for-the-badge" alt="PostgreSQL 15" />
  <img src="https://img.shields.io/badge/Redis-7-dc382d?style=for-the-badge" alt="Redis 7" />
  <img src="https://img.shields.io/badge/Kafka-3.9-231f20?style=for-the-badge" alt="Kafka 3.9" />
</p>

Customers order food, merchants accept and prepare it, riders deliver it, and admins handle refunds and recovery. All
four roles share one Spring Boot backend and one React app.

> Keep the critical business transaction correct first. Distribute side effects only after the database commit.

---

## Highlights

- **Correct checkout**: idempotency keys, row locks on the cart, menu item stock that cannot oversell, and money stored
  with its currency.
- **Transactional outbox → Kafka**: order changes and their events commit together. Events stay in order per order,
  follow published JSON schemas, and are deduplicated by every consumer.
- **Event-driven fulfilment**: merchant inbox, deadlines, rider dispatch and order completion run as separate modules
  that only talk through events.
- **Declared state machines** for orders, deliveries, after-sales cases and refunds.
- **After-sales refunds**: merchant answers, counter-offers, platform arbitration, deadlines, and retried refunds.
- **Live consoles**: the server pushes change signals over SSE, and a slower poll is kept as a fallback.
- **Operable**: OpenTelemetry tracing, Prometheus metrics and alerts, Grafana, dead-letter replay, Kubernetes manifests
  and a gated CI/CD pipeline.

---

## Architecture

```mermaid
flowchart LR
  Browser[React app] -- REST + SSE --> API[Spring Boot API]
  API --> DB[(PostgreSQL)]
  API --> Redis[(Redis: sessions, cache, rate limits, push fan-out)]
  DB --> Outbox[Outbox publisher]
  Outbox --> Kafka[(Kafka)]
  Kafka --> Modules[Merchant inbox / deadlines / dispatch / notifications / after-sales]
  Modules --> DB
  Modules -. failed records .-> DLT[(Dead letters, admin replay)]
```

| Layer | Stack |
| --- | --- |
| Frontend | React 19, strict TypeScript, Vite, Vitest |
| Backend | Java 17, Spring Boot 4.2, Spring Security 7, Spring Data JDBC, Flyway |
| Data and messaging | PostgreSQL, Redis, Kafka |
| Observability and delivery | OpenTelemetry, Prometheus, Grafana, Docker, Kubernetes (kustomize), GitHub Actions |

---

## Measured

On one 15-core laptop that also ran k6 and the Docker services. Each row links to the run and its conditions.

| What | Result | Details |
| --- | --- | --- |
| Catalog reads through the two-level cache, 50 VUs | 16.1k → 52.5k req/s, p99 9.2 → 2.8 ms; 4 of 5.0M reads reached PostgreSQL | [Catalog Caching](docs/correctness.md#catalog-caching) |
| Order flow (login, menus, cart, checkout, order list), 300 VUs | 820 req/s, p95 82 ms, p99 270 ms, no failed request | [Connections: listener threads and the pool](docs/operations.md#connections-listener-threads-and-the-pool) |
| The same at 500 VUs | 1,120 req/s; the machine's CPU is the limit, with 20 or 30 database connections alike | same |
| Consumer threads per listener, 500 VUs | a paid order reaches the merchant inbox in 2.2 s (p95) with 3 threads, in 44 s with 1 | same |
| Stock race: 400 buyers pay at the same instant for the last 100 units of one dish | exactly 100 paid, 300 got `409 OUT_OF_STOCK`, no other error and 0 left, in each of 3 runs; all 400 answered within 0.37 s | [Stock](docs/correctness.md#stock) |
| Outbox claim query, 100k unpublished events behind 1M published ones | 115-225 ms → 0.1 ms with the partial index | [ADR 0001](docs/adr/0001-order-sharding.md#what-the-repository-measured) |
| One pod at the base limits (1Gi, 2 CPUs), G1 | about 120 req/s of the order flow; GC pauses of 5 ms on average, 9 ms at most, below saturation; 644 MiB at peak | [Container image](docs/operations.md#3-container-image) |

---

## Quick Start

You need Java 17, Node 24 and Docker.

```sh
# 1. PostgreSQL (5433), Redis (6379) and Kafka (9092)
(cd backend && docker compose up -d)

# 2. Backend with demo restaurants and accounts
(cd backend && FLYWAY_LOCATIONS=classpath:db/migration,classpath:db/demo ./gradlew bootRun)

# 3. Frontend dev server
(cd frontend && npm ci && npm start)
```

Open:
- [http://localhost:3000](http://localhost:3000) for the app;
- [http://localhost:8080/swagger-ui/index.html](http://localhost:8080/swagger-ui/index.html) for the API.

| Demo login | Password | Roles |
| --- | --- | --- |
| `demo@example.com` | `demo123` | customer and admin |

The demo seed (`backend/src/main/resources/db/demo`) also creates a merchant login per restaurant and three rider
logins.

**Without PostgreSQL and Kafka:** `(cd backend && SPRING_PROFILES_ACTIVE=h2 ./gradlew bootRun)` runs the backend on
H2. Redis is still needed. Add the `demo` profile (`h2,demo`) for short after-sales deadlines; see
[docs/after-sales.md](docs/after-sales.md#after-sales-demo).

### What each role sees

| Role | Workspace |
| --- | --- |
| Customer | Menus, cart, checkout, orders with history, notifications, refund requests |
| Merchant | The restaurant's orders (accept, reject, prepare) and its refund requests |
| Rider | Duty switch and own deliveries (picked up, delivered) |
| Admin | Merchant and rider accounts, order tools, dead-letter replay, refund arbitration and retries |

A user with several roles switches between workspaces with tabs. Details:
[docs/frontend-workspaces.md](docs/frontend-workspaces.md).

---

## Tests

```sh
# Backend (Testcontainers suites need Docker)
(cd backend && ./gradlew test -x buildFrontend -x syncFrontendBuild --no-daemon)

# Frontend
(cd frontend && npm run typecheck && npm run lint && npm test && npm run build)
```

CI also runs coverage gates, SpotBugs, CodeQL and config linting. See [docs/testing.md](docs/testing.md) for every
command.

---

## Project Layout

```text
backend/        Spring Boot app: controllers, services, fulfilment modules, after-sales, messaging, Flyway migrations
frontend/       React + TypeScript client
deploy/         Kubernetes manifests and the observability stack (Prometheus, Grafana, Tempo)
load-tests/     k6 load tests
docs/           design notes, operations runbook and ADRs
```

---

## Documentation

| Topic | Where |
| --- | --- |
| REST endpoints | [docs/api.md](docs/api.md) |
| Checkout, stock, money, time, order states, outbox, event contracts, caching, read replicas, security and rate limits | [docs/correctness.md](docs/correctness.md) |
| Order fulfilment: merchant inbox, deadlines, dispatch, out-of-order events | [docs/fulfilment.md](docs/fulfilment.md) |
| After-sales refunds and the after-sales demo | [docs/after-sales.md](docs/after-sales.md) |
| Push signals (SSE) | [docs/push-signals.md](docs/push-signals.md) |
| Data tier: partitions, order numbers, sharding plan | [docs/data-tier.md](docs/data-tier.md) |
| Frontend workspaces | [docs/frontend-workspaces.md](docs/frontend-workspaces.md) |
| Tests and useful commands | [docs/testing.md](docs/testing.md) |
| Deployment, observability, CI/CD, runbooks | [docs/operations.md](docs/operations.md) |
| Design decisions | [docs/adr/](docs/adr/) (0001 sharding, 0002 state machines and after-sales, 0003 push signals) |
| Known limitations | [docs/limitations.md](docs/limitations.md) |
| Working on the code | [AGENTS.md](AGENTS.md) |

---

## Limitations

This is a demo, so some parts are simulated or missing:

- **Simulated:** payment and refunds.
- **Missing:** taxes, discounts, tips and currency conversion.
- **Stock**: the menu shows none, so a sold-out item shows up at payment, and the cart reserves nothing.
- **Kafka** delivery is at-least-once, and consumers deduplicate.
- **Push** is best effort, with polling as the fallback.
- **Sharding** is planned, not built.

The full list is in [docs/limitations.md](docs/limitations.md).

---

## License

[MIT](LICENSE)
