# Frontend Brief - Luc1fer's Food Bank / OnlineOrder

## 1. Product Goal

Build a food-ordering frontend for a Spring Boot backend that supports:

- Restaurant and menu discovery.
- Account signup, login, logout, and session restore.
- Authenticated cart building with quantity editing.
- Checkout with idempotency protection.
- Demo payment checkout that creates a paid order.
- Customer order history, cancellation, and order notifications.
- Admin-only order status transitions and Kafka dead-letter replay tooling.

The backend is intentionally stateful and correctness-focused: it uses server sessions, PostgreSQL transactions/locks, idempotency records, an outbox, Kafka, Redis session/cache/rate-limit state, and asynchronous notifications.

## 2. Backend Integration Notes

- Base URL: same origin in the current app. Existing frontend calls relative URLs such as `/login` and `/cart`.
- JSON naming: `spring.jackson.property-naming-strategy=SNAKE_CASE`, so all JSON fields should be sent and read as `snake_case`.
- Date/time fields: Java `LocalDateTime` serialized as ISO-like strings, for example `2026-05-02T15:30:00`.
- Authentication: Spring Security form login with a server-side session cookie. Use `credentials: "include"` if the frontend is served from a different origin during development.
- CSRF: disabled in backend config.
- CORS: no explicit CORS config. Same-origin deployment is the expected path unless backend CORS is added.
- Traceability: every HTTP response includes `X-Trace-Id`; surface this in debug/error tooling if useful.
- Rate limits exist on login, signup, checkout, payment checkout, admin order status, and dead-letter replay.

## 3. Main User Flows

### Guest / Authentication

1. User lands on storefront.
2. Frontend calls `GET /me` to restore an existing session.
3. If unauthenticated, user can browse public restaurant/menu data if the UI allows it.
4. User signs up with email/password/name or logs in with email/password.
5. On login success, backend sets/uses the session cookie and returns `200` with no body.

### Browse and Cart

1. Load restaurants and menus from `GET /restaurants/menu` or `GET /restaurant/{restaurantId}/menu`.
2. User selects a restaurant and views menu item cards.
3. User adds an item with `POST /cart`; adding an existing item increments quantity.
4. Frontend refreshes `GET /cart` to get authoritative item IDs, quantities, and total.
5. User updates quantity with `PUT /cart/items/{orderItemId}` or removes with `DELETE /cart/items/{orderItemId}`.

### Checkout / Payment

1. User opens cart review.
2. Frontend generates a stable `Idempotency-Key` for one checkout attempt.
3. Preferred customer flow is `POST /payments/checkout` with payment details; backend validates fields and creates a `PAID` order.
4. Alternative no-payment flow is `POST /cart/checkout`; backend creates a `PLACED` order.
5. On success, backend returns an `OrderDto`, clears the cart, and eventually emits notification events.
6. If a request is retried with the same idempotency key and same logical request, backend returns the stored order instead of creating a duplicate.

### Orders and Notifications

1. User opens order history.
2. Frontend calls `GET /orders`; results are current user's orders sorted newest first.
3. User can cancel an owned order via `POST /orders/{orderId}/cancel` when the state machine permits it.
4. Frontend can poll `GET /notifications` to show order lifecycle messages. These are eventually consistent because they come from async order events.

### Admin Operations

1. Admin updates order status with `PATCH /orders/{orderId}/status`.
2. Admin can replay one dead-letter event with `POST /dead-letters/{deadLetterEventId}/replay`.
3. Current backend does not expose roles in `GET /me`, does not expose a list-all-orders endpoint, and does not expose a dead-letter list endpoint. An admin UI will need manual ID input or backend additions.

## 4. Page List and Required Data

| Page / View | Purpose | Data to Display | Primary Actions / APIs | Important States |
| --- | --- | --- | --- | --- |
| Auth / Session Gate | Restore session, login, signup | Current user if authenticated; login/signup forms | `GET /me`, `POST /login`, `POST /signup`, `POST /logout` | Loading session, unauthenticated, login failed, account locked/disabled returns `401`, signup email conflict |
| Restaurant Directory | Show available restaurants | `id`, `name`, `address`, `phone`, `image_url`, optional menu preview | `GET /restaurants/menu` | Empty restaurant list, image fallback, loading/error |
| Menu Browser | Show dishes for selected restaurant | Menu item `id`, `restaurant_id`, `name`, `description`, `price`, `image_url`; current cart quantity per menu item | `GET /restaurant/{restaurantId}/menu`, `GET /cart`, `POST /cart`, `PUT /cart/items/{orderItemId}` | Public browsing possible; add/update requires auth; unknown restaurant returns an empty list |
| Cart Drawer / Cart Page | Review and edit cart | Cart `id`, `total_price`, `order_items`; each item image/name/price/quantity/subtotal | `GET /cart`, `POST /cart`, `PUT /cart/items/{orderItemId}`, `DELETE /cart/items/{orderItemId}` | Empty cart, quantity update pending, remove pending, `400` invalid quantity |
| Payment / Checkout | Collect payment details and place order | Cart total, item count, payment fields | `POST /payments/checkout` with `Idempotency-Key` | Empty cart disabled, card validation errors, expired card, duplicate request handling, retry-safe in-flight state |
| Order Confirmation | Confirm successful checkout | Returned `OrderDto`: `id`, `status`, `total_price`, `created_at`, `items` | Response from checkout endpoint | `PAID` for payment flow, `PLACED` for cart checkout |
| Order History | Show customer order archive | Order list and item snapshots | `GET /orders`, optional `POST /orders/{orderId}/cancel` | Empty orders, terminal statuses (`COMPLETED`, `CANCELLED`), invalid cancellation conflict |
| Notifications | Show lifecycle updates | `id`, `order_id`, `event_type`, `title`, `message`, `created_at` | `GET /notifications` | May lag checkout/status updates; can be empty if async consumer has not processed events |
| Admin Order Status | Move orders through state machine | Order ID input, target status, returned order | `PATCH /orders/{orderId}/status` | Requires `ROLE_ADMIN`; no backend list-all-orders endpoint; invalid transitions return `409` |
| Admin Dead-Letter Replay | Replay Kafka consumer dead-letter event | Dead-letter event ID input and replay result fields | `POST /dead-letters/{deadLetterEventId}/replay` | Requires `ROLE_ADMIN`; endpoint exists only when Kafka is enabled; no list endpoint |

## 5. Shared Data Shapes

### CurrentUserDto

```json
{
  "id": 1,
  "email": "demo@laifood.com",
  "first_name": "Demo",
  "last_name": "User"
}
```

Note: roles/admin flags are not included.

### RestaurantDto

Returned by `GET /restaurants/menu`.

```json
{
  "id": 1,
  "name": "Burger King",
  "address": "773 N Mathilda Ave, Sunnyvale, CA 94085",
  "phone": "(408) 736-0101",
  "image_url": "https://...",
  "menu_items": [
    {
      "id": 1,
      "name": "Whopper",
      "description": "Burger description",
      "price": 6.39,
      "image_url": "https://..."
    }
  ]
}
```

`menu_items` can be `null` for a restaurant without menu items because the service passes through a missing grouped value.

### MenuItemEntity

Returned by `GET /restaurant/{restaurantId}/menu`.

```json
{
  "id": 1,
  "restaurant_id": 1,
  "name": "Whopper",
  "description": "Burger description",
  "price": 6.39,
  "image_url": "https://..."
}
```

### CartDto

```json
{
  "id": 1,
  "total_price": 12.78,
  "order_items": [
    {
      "order_item_id": 10,
      "menu_item_id": 1,
      "restaurant_id": 1,
      "price": 6.39,
      "quantity": 2,
      "menu_item_name": "Whopper",
      "menu_item_description": "Burger description",
      "menu_item_image_url": "https://..."
    }
  ]
}
```

Use `order_item_id` for quantity updates/removal. Use `menu_item_id` for add-to-cart.

### OrderDto

```json
{
  "id": 101,
  "total_price": 12.78,
  "status": "PAID",
  "created_at": "2026-05-02T15:30:00",
  "items": [
    {
      "id": 1001,
      "menu_item_id": 1,
      "restaurant_id": 1,
      "price": 6.39,
      "quantity": 2,
      "menu_item_name": "Whopper",
      "menu_item_description": "Burger description",
      "menu_item_image_url": "https://..."
    }
  ]
}
```

Order item snapshots are copied at checkout time; they do not depend on later menu changes.

### OrderNotificationDto

```json
{
  "id": 1,
  "order_id": 101,
  "event_type": "order.paid",
  "title": "Payment confirmed",
  "message": "Order #101 was paid successfully with 2 item(s).",
  "created_at": "2026-05-02T15:31:00"
}
```

Known event types:

- `order.created`
- `order.paid`
- `order.accepted`
- `order.preparing`
- `order.completed`
- `order.cancelled`

### DeadLetterReplayDto

```json
{
  "id": 7,
  "source_topic": "order-events",
  "dead_letter_topic": "order-events.dlt",
  "message_key": "101",
  "replay_status": "REPLAYED",
  "replay_attempts": 1,
  "replayed_at": "2026-05-02T15:35:00",
  "last_replay_error": null,
  "created_at": "2026-05-02T15:34:00",
  "updated_at": "2026-05-02T15:35:00"
}
```

## 6. API Endpoints

### Authentication and Session

#### `GET /me`

- Auth: required.
- Request body: none.
- Response `200`: `CurrentUserDto`.
- Errors:
  - `401 Unauthorized` when no valid session.
  - `404 Not Found` if session principal no longer maps to a customer.

#### `POST /login`

- Auth: public.
- Content type: `application/x-www-form-urlencoded;charset=UTF-8`.
- Request fields:
  - `username`: email.
  - `password`: password.
- Response `200`: no body.
- Side effects: successful login records `last_login_at`, clears failed login state, and uses a session cookie.
- Errors:
  - `401 Unauthorized` for bad credentials, disabled account, or account locked after repeated failures.
  - `429 Too Many Requests` after 10 login attempts per rate-limit window per IP.

Example:

```http
POST /login
Content-Type: application/x-www-form-urlencoded;charset=UTF-8

username=demo%40laifood.com&password=demo123
```

#### `POST /logout`

- Auth: public in security config, but meaningful when session exists.
- Request body: none.
- Response `200`: no body.
- Side effects: deletes `SESSION` and `JSESSIONID` cookies.

#### `POST /signup`

- Auth: public.
- Request body:

```json
{
  "email": "new@example.com",
  "password": "secret123",
  "first_name": "New",
  "last_name": "User"
}
```

- Response `201`: no body.
- Side effects: creates customer, assigns `ROLE_USER`, creates empty cart.
- Errors:
  - `409 Conflict` when email already exists.
  - `429 Too Many Requests` after 5 signup attempts per rate-limit window per IP.
  - Backend currently does not explicitly validate blank/null signup fields before calling service; frontend should validate required fields.

### Restaurants and Menu

#### `GET /restaurants/menu`

- Auth: public.
- Request body: none.
- Response `200`: `RestaurantDto[]`.
- Use for the restaurant directory and initial menu previews.

#### `GET /restaurant/{restaurantId}/menu`

- Auth: public.
- Path params:
  - `restaurantId`: numeric restaurant ID.
- Response `200`: `MenuItemEntity[]`.
- Notes:
  - Unknown restaurant ID returns `[]`, not `404`.
  - No backend search/filter query params; search should be frontend-side unless a backend endpoint is added.

### Cart

#### `GET /cart`

- Auth: required.
- Response `200`: `CartDto`.
- Errors:
  - `401 Unauthorized`.
  - `404 Not Found` if customer/cart is missing.

#### `POST /cart`

- Auth: required.
- Request body:

```json
{
  "menu_id": 1
}
```

- Response `200`: no body.
- Side effects:
  - Creates cart line if missing.
  - Increments quantity by 1 if the same menu item is already in cart.
  - Recalculates cart total.
- Errors:
  - `401 Unauthorized`.
  - `404 Not Found` if menu item or cart is missing.

#### `PUT /cart/items/{orderItemId}`

- Auth: required.
- Path params:
  - `orderItemId`: cart line item ID from `CartDto.order_items[].order_item_id`.
- Request body:

```json
{
  "quantity": 3
}
```

- Response `200`: no body.
- Side effects:
  - `quantity > 0`: sets exact quantity.
  - `quantity = 0`: deletes item.
  - Recalculates cart total.
- Errors:
  - `400 Bad Request` when `quantity` is null or negative.
  - `404 Not Found` when item is not in the user's cart and requested quantity is nonzero.
  - `401 Unauthorized`.

#### `DELETE /cart/items/{orderItemId}`

- Auth: required.
- Response `200`: no body.
- Side effects: same as setting quantity to `0`.
- Notes: deleting a missing/not-owned item is idempotent and returns success after syncing total.

#### `POST /cart/checkout`

- Auth: required.
- Headers:
  - `Idempotency-Key`: required, nonblank.
- Request body: none.
- Response `200`: `OrderDto` with `status: "PLACED"`.
- Side effects:
  - Creates order and order item snapshots.
  - Enqueues order event.
  - Clears cart and resets total.
  - Stores idempotency result.
- Errors:
  - `400 Bad Request` when `Idempotency-Key` is missing/blank or cart is empty.
  - `409 Conflict` when key is reused with a different cart/request fingerprint or prior operation is inconsistent.
  - `429 Too Many Requests` after 20 checkout attempts per rate-limit window per authenticated user.

### Payment Checkout

#### `POST /payments/checkout`

- Auth: required.
- Headers:
  - `Content-Type: application/json`
  - `Idempotency-Key`: required, nonblank.
- Request body:

```json
{
  "cardholder_name": "Demo User",
  "card_number": "4242424242424242",
  "expiry": "12/30",
  "cvv": "123"
}
```

- Response `200`: `OrderDto` with `status: "PAID"`.
- Validation:
  - `cardholder_name`: required after trim.
  - `card_number`: must contain 16 digits after removing non-digits.
  - `cvv`: must contain 3 or 4 digits after removing non-digits.
  - `expiry`: must be `MM/YY` and not expired.
- Errors:
  - `400 Bad Request` for missing payment details, invalid card fields, missing idempotency key, or empty cart.
  - `409 Conflict` when idempotency key is reused with a different logical request.
  - `429 Too Many Requests` after 20 payment checkout attempts per rate-limit window per authenticated user.
- Security note: this is a demo/local validation flow, not a real payment processor integration. Do not persist or log card details in the frontend.

### Orders

#### `GET /orders`

- Auth: required.
- Response `200`: `OrderDto[]`, sorted newest first.
- Errors:
  - `401 Unauthorized`.

#### `POST /orders/{orderId}/cancel`

- Auth: required; only cancels an order owned by the current user.
- Headers:
  - `Idempotency-Key`: required, nonblank, max 255 characters.
- Path params:
  - `orderId`: numeric order ID.
- Request body: none.
- Response `200`: `OrderDto` with updated status, normally `CANCELLED`.
- Allowed cancellation states:
  - `PLACED -> CANCELLED`
  - `PAID -> CANCELLED`
  - `ACCEPTED -> CANCELLED`
- Errors:
  - `400 Bad Request` when idempotency key is missing/too long.
  - `404 Not Found` when order does not exist or is not owned by current user.
  - `409 Conflict` for invalid state transitions such as `PREPARING -> CANCELLED`, terminal states, reused key with different request, or in-progress idempotent operation.

#### `PATCH /orders/{orderId}/status`

- Auth: required with `ROLE_ADMIN`.
- Headers:
  - `Idempotency-Key`: required, nonblank, max 255 characters.
- Request body:

```json
{
  "status": "ACCEPTED"
}
```

- Response `200`: `OrderDto`.
- Allowed transitions:
  - `PLACED -> PAID`
  - `PLACED -> CANCELLED`
  - `PAID -> ACCEPTED`
  - `PAID -> CANCELLED`
  - `ACCEPTED -> PREPARING`
  - `ACCEPTED -> CANCELLED`
  - `PREPARING -> COMPLETED`
  - `COMPLETED` and `CANCELLED` are terminal.
- Same-status update returns the current order without a transition event.
- Errors:
  - `400 Bad Request` for missing/unsupported status or invalid idempotency key.
  - `401 Unauthorized` if unauthenticated.
  - `403 Forbidden` if authenticated without `ROLE_ADMIN`.
  - `404 Not Found` if order does not exist.
  - `409 Conflict` for invalid transition or idempotency conflict.
  - `429 Too Many Requests` after 30 requests per rate-limit window per authenticated user.

### Notifications

#### `GET /notifications`

- Auth: required.
- Response `200`: `OrderNotificationDto[]`, sorted newest first.
- Errors:
  - `401 Unauthorized`.
- Notes:
  - Notifications are generated by Kafka consumer processing of order events.
  - They may not appear immediately after checkout/status updates.

### Admin Dead-Letter Replay

#### `POST /dead-letters/{deadLetterEventId}/replay`

- Auth: required with `ROLE_ADMIN`.
- Availability: controller/service are loaded only when `app.kafka.enabled=true`.
- Path params:
  - `deadLetterEventId`: numeric dead-letter record ID.
- Request body: none.
- Response `200`: `DeadLetterReplayDto`.
- Behavior:
  - If already `REPLAYED`, returns existing replay state.
  - Sends payload back to the original `source_topic`.
  - Marks replay success/failure in storage.
- Errors:
  - `400 Bad Request` when dead-letter event lacks a source topic.
  - `401 Unauthorized`.
  - `403 Forbidden` without `ROLE_ADMIN`.
  - `404 Not Found` when event ID does not exist.
  - `429 Too Many Requests` after 10 replay attempts per rate-limit window per authenticated user.
  - `500 Internal Server Error` when Kafka send/replay fails.

### Operational / Non-Product Endpoints

These should not be part of the customer shopping UI unless building an ops/debug surface.

| Endpoint | Auth | Notes |
| --- | --- | --- |
| `GET /actuator/health` | Public | Health check. |
| `GET /actuator/info` | Public | App info. |
| `GET /actuator/metrics`, `GET /actuator/prometheus` | `ROLE_ADMIN` | Operational metrics. |
| `GET /hello?name=Guest` | Authenticated by current security config | Demo controller exists, but security config does not explicitly permit it, so it falls under authenticated routes. Not product-facing. |
| `/h2-console/**` | Public in security config | Local/H2 profile debugging only. |

## 7. Error Handling Contract

There is no custom global error DTO. Most application exceptions use Spring `@ResponseStatus`:

- `BadRequestException` -> `400`
- `ResourceNotFoundException` -> `404`
- `ConflictException` -> `409`

Spring Boot's default JSON error body can vary by environment. Do not design the frontend around a guaranteed `message` field for these errors. Prefer status-driven copy, with optional raw body shown in debug views.

Rate-limit responses are custom:

```json
{
  "message": "Too many requests"
}
```

Common status handling:

| Status | Meaning | UX Guidance |
| --- | --- | --- |
| `200` | Success | Parse JSON only when `Content-Type` is JSON; several endpoints return empty body. |
| `201` | Signup created | Show account-created state and route to login. |
| `400` | Invalid input | Keep form open, mark fields, show actionable validation message. |
| `401` | Unauthenticated or invalid login | Clear current user/session state and show login. |
| `403` | Authenticated but lacks admin role | Hide/admin-deny privileged actions. |
| `404` | Resource not found or not owned | Show missing item/order state; for owned order cancel this can mean not owned. |
| `409` | Business conflict | Show retry/conflict copy: invalid status transition, empty/stale cart assumptions, or idempotency key reused for different request. |
| `429` | Rate limited | Disable submit briefly and ask user to retry later. |
| `500` | Server/infrastructure failure | Show generic failure and include `X-Trace-Id` in support/debug details. |

## 8. Authentication Details

- Login endpoint is Spring Security form login, not JSON login.
- Request form fields are `username` and `password`; `username` is the email.
- User lookup is case-insensitive.
- Backend locks account after 5 failed login attempts for 15 minutes.
- Session timeout default is `30m`.
- Session cookie config:
  - HTTP-only.
  - SameSite `lax`.
  - Secure controlled by `SESSION_COOKIE_SECURE`, default `false`.
- `GET /me` is the source of truth for whether the session is active.
- Backend does not return role/authority data in `GET /me`; admin UI capability discovery needs backend enhancement or a separate config/assumption.

## 9. Idempotency Guidance for Frontend

Use a new random key, such as `crypto.randomUUID()`, for each logical mutation attempt:

- `POST /cart/checkout`
- `POST /payments/checkout`
- `POST /orders/{orderId}/cancel`
- `PATCH /orders/{orderId}/status`

Keep the same key while retrying the exact same in-flight attempt. Generate a new key when the user changes the cart, payment payload, target status, or starts a new action.

Never reuse an idempotency key across different endpoints, orders, cart snapshots, or payment details.

## 10. Order Status Model

Statuses:

- `PLACED`
- `PAID`
- `ACCEPTED`
- `PREPARING`
- `COMPLETED`
- `CANCELLED`

Suggested customer-facing grouping:

- Active: `PLACED`, `PAID`, `ACCEPTED`, `PREPARING`
- Complete: `COMPLETED`
- Cancelled: `CANCELLED`

Cancellation button should be visible only for owned orders in:

- `PLACED`
- `PAID`
- `ACCEPTED`

Admin transition controls should only offer valid next statuses from the current status.

## 11. Backend Gaps / Frontend Design Constraints

- No `GET /restaurants`; actual implemented aggregate endpoint is `GET /restaurants/menu`.
- No paginated/search/filter endpoints for restaurants, menus, orders, or notifications.
- No all-orders endpoint for admin order management.
- No order detail endpoint by ID.
- No role/authority field in `GET /me`.
- No notification read/unread state or mark-as-read endpoint.
- No dead-letter list endpoint.
- No address/delivery-time/order-note fields.
- No real payment provider integration.
- No inventory/stock validation.
- Error response body is not standardized.

Design should either work within these constraints or call out the backend additions needed for richer admin, notification, and checkout experiences.

## 12. Recommended Frontend Implementation Priorities

1. Session/auth shell: `GET /me`, login, signup, logout.
2. Restaurant/menu browser with frontend search and image fallbacks.
3. Cart drawer/page with optimistic quantity controls but authoritative refresh after each mutation.
4. Payment checkout step with client-side card field validation and one stable idempotency key per attempt.
5. Order confirmation and order history.
6. Notifications panel with polling or manual refresh.
7. Optional customer cancel action with valid-state gating.
8. Optional admin tools, after adding role visibility or accepting a manual/admin-only route.
