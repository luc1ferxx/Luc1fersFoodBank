# Core API Map

## Customer APIs

| Method | Path | Purpose |
| --- | --- | --- |
| `POST` | `/signup` | Create a customer account |
| `POST` | `/login` | Start a server-side session |
| `POST` | `/logout` | End the session |
| `GET` | `/me` | Read current session user: `roles`, plus the bound `merchant` / `rider` (or `null`) for staff logins |
| `GET` | `/restaurants/menu` | List restaurants with their menus (HTTP-cacheable, see [Catalog Caching](correctness.md#catalog-caching)) |
| `GET` | `/restaurant/{restaurantId}/menu` | List menu items; `404` for an unknown restaurant (HTTP-cacheable) |
| `GET` | `/cart` | Read current cart |
| `POST` | `/cart` | Add menu item to cart |
| `PUT` | `/cart/items/{orderItemId}` | Update quantity |
| `DELETE` | `/cart/items/{orderItemId}` | Remove item |
| `DELETE` | `/cart` | Empty the cart (`204`, also when it is already empty or the account has no cart), for example after `409 CART_RESTAURANT_CONFLICT` |
| `POST` | `/cart/checkout` | Checkout cart with idempotency key |
| `POST` | `/payments/checkout` | Payment-style checkout with idempotency key |
| `GET` | `/orders` | List current user's orders; each carries `id` (the key the order paths take) and `order_no` (the public order number, a JSON string) |
| `POST` | `/orders/{orderId}/cancel` | Cancel an eligible order (a paid one with a captured payment above 0.00 is refunded automatically) |
| `GET` | `/orders/{orderId}/history` | The order's status changes, oldest first (owner or admin; `404` for anyone else) |
| `GET` | `/notifications` | List order notifications |
| `GET` | `/events/stream` | Server-Sent Events: change signals for the login's lists (see [Push Signals](push-signals.md#push-signals-sse)) |
| `GET`, `POST` | `/after-sales/cases/**`, `/orders/{orderId}/after-sales` | Refund requests and the answers to them (see [After-Sales Refunds](after-sales.md#after-sales-refunds)) |

## Admin and Recovery APIs

| Method | Path | Purpose |
| --- | --- | --- |
| `PATCH` | `/orders/{orderId}/status` | Move order through the status state machine |
| `GET` | `/admin/outbox/events/failed?limit=50` | Inspect failed outbox events |
| `POST` | `/admin/outbox/events/{eventId}/retry` | Reset failed outbox event to `PENDING` |
| `POST` | `/admin/outbox/events/{eventId}/abandon` | Give up on a failed event that can never be published (`{"reason": ...}`, audited), releasing the later events of its aggregate |
| `PATCH` | `/admin/menu-items/{menuItemId}` | Update a menu item's `name`, `description` or `price`; evicts that restaurant's cached menu after commit |
| `PATCH` | `/admin/restaurants/{restaurantId}` | Update a restaurant's `name`, `address`, `phone` or `image_url`; evicts the cached restaurant list after commit |
| `POST` | `/dead-letters/{deadLetterEventId}/replay` | Replay a stored dead-letter event |
| `GET`, `POST` | `/admin/merchants`, `/admin/riders` | List or provision merchant and rider logins (see [Provisioning merchants and riders](fulfilment.md#provisioning-merchants-and-riders)) |
| `PATCH` | `/admin/merchants/{merchantId}`, `/admin/riders/{riderId}` | Activate or deactivate a merchant or rider, body `{"active": false}` |
| `GET`, `POST` | `/admin/after-sales/**`, `/admin/refunds/**` | After-sales queue and arbitration, refunds and the retry of a failed one (see [After-Sales Refunds](after-sales.md#after-sales-refunds)) |
| `GET` | `/admin/audit-log?limit=50&before_id=` | Audit log of the admin actions above and of the riders' own changes (see [Order State](correctness.md#order-state)), newest first; pass the last entry's `id` as `before_id` for the next page |

## Operational APIs

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/actuator/health` | Aggregate health |
| `GET` | `/actuator/health/liveness` | Liveness probe |
| `GET` | `/actuator/health/readiness` | Readiness probe |
| `GET` | `/actuator/metrics` | Metric discovery |
| `GET` | `/actuator/prometheus` | Prometheus scrape endpoint |
| `GET` | `/v3/api-docs` | OpenAPI JSON |
| `GET` | `/swagger-ui/index.html` | Swagger UI |

With `MANAGEMENT_SERVER_PORT=8081` (set on Kubernetes and in the observability stack) the `/actuator` endpoints move
to that in-cluster port and need no session there; without it they stay on the application port and everything but
health and info requires `ROLE_ADMIN`. See [docs/operations.md](operations.md).

API errors use a consistent JSON shape and include the OpenTelemetry trace id, which is also returned in the
`X-Trace-Id` header (send a W3C `traceparent` header to join an existing trace):

```json
{
  "code": "VALIDATION_FAILED",
  "message": "Request validation failed",
  "trace_id": "4bf92f3577b34da6a3ce929d0e0e4736",
  "status": 400,
  "path": "/signup",
  "timestamp": "2026-05-07T05:00:00Z"
}
```
