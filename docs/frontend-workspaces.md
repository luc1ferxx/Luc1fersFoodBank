# Frontend workspaces

After sign-in the UI reads `roles` from `GET /me` and opens one workspace per role. Users with several roles switch
between them with the tabs under the header; guests and logins without a known role get the ordering page.

| Role | Workspace |
| --- | --- |
| `ROLE_USER`, guests | Order food: menus, cart, checkout, orders with their status history (`GET /orders/{id}/history`), notifications. Adding another restaurant's item (`409 CART_RESTAURANT_CONFLICT`) asks whether to clear the cart (`DELETE /cart`) and add it. A failed add or decrease shows its error on the menu card used. Signed-in customers also get "Request a refund" on a completed order within the request window (whole order or chosen lines), their refund cases with a countdown, accept or decline a counter-offer, escalate, withdraw (a first request withdrawn before a rejection then offers "Request a refund again (1 left)"), and the automatic refund of a cancelled paid order. |
| `ROLE_MERCHANT` | Merchant console, "Orders" tab: the restaurant's orders (`GET /merchant/orders`, status filter), accept, reject with a reason, mark preparing. "After-sales" tab: the restaurant's refund requests; approve, reject with a reason, or offer less than requested, until the deadline. |
| `ROLE_RIDER` | Rider console: on-duty switch, the rider's own deliveries, picked up and delivered. |
| `ROLE_ADMIN` | Admin console: list, create, and deactivate merchants (restaurant picked from the catalog) and riders; the manual order-status change and dead-letter replay; the after-sales queue by status, a case's history with its refund attempts and last error, arbitration, and the failed refunds with Retry. |

Switching workspaces keeps the ordering page and the admin console as they were, so a checkout, cancel, status
change, refund request or arbitration retried after a switch reuses its Idempotency-Key. A signed-in user's browser
holds one change-signal stream (`GET /events/stream`, ADR 0003): the merchant and rider consoles and the notifications
read again on each signal of their list while the tab is visible, and poll only every 60 seconds while the stream is
open, every 15 seconds while it is not. The customer's refund cases are read every 10 seconds while the page is
shown and a case is still open or refunding, and together with the order list whenever a notification read (on a
signal or a poll) brings a new notification, so an order that completes gets "Request a refund" without a refresh; the
admin's after-sales section reads when opened and on Refresh. A
deactivated merchant or rider (the backend answers `403` on `/merchant/**` and `/rider/**`) gets a notice instead of
the raw error, polling stops, and Refresh checks again. A request the rate limiter refused (`429 RATE_LIMITED`) says
"Too many requests. Try again in N seconds." in every workspace, N from its `Retry-After`.
