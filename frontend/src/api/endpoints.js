import { createIdempotencyKey, request } from "./client";

const jsonHeaders = { "Content-Type": "application/json" };

export const login = ({ username, password }, { signal } = {}) =>
  request("/login", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8" },
    body: new URLSearchParams({ username, password }).toString(),
    signal,
    fallbackMessage: "Fail to log in",
    reportUnauthorized: false,
  });

export const logout = () =>
  request("/logout", {
    method: "POST",
    fallbackMessage: "Fail to log out",
    reportUnauthorized: false,
  });

export const signup = (data) =>
  request("/signup", {
    method: "POST",
    headers: jsonHeaders,
    body: JSON.stringify(data),
    fallbackMessage: "Fail to sign up",
  });

export const getCurrentUser = ({ signal } = {}) =>
  request("/me", {
    signal,
    fallbackMessage: "Fail to load your session",
    reportUnauthorized: false,
  });

export const getRestaurants = ({ signal } = {}) =>
  request("/restaurants/menu", { signal, fallbackMessage: "Fail to get restaurants" });

export const getMenus = (restaurantId, { signal } = {}) =>
  request(`/restaurant/${restaurantId}/menu`, { signal, fallbackMessage: "Fail to get menus" });

export const getCart = ({ signal } = {}) =>
  request("/cart", { signal, fallbackMessage: "Fail to get shopping cart data" });

export const addItemToCart = (menuItemId) =>
  request("/cart", {
    method: "POST",
    headers: jsonHeaders,
    body: JSON.stringify({ menu_id: menuItemId }),
    fallbackMessage: "Fail to add menu item to shopping cart",
  });

export const updateCartItem = (orderItemId, quantity) =>
  request(`/cart/items/${orderItemId}`, {
    method: "PUT",
    headers: jsonHeaders,
    body: JSON.stringify({ quantity }),
    fallbackMessage: "Fail to update shopping cart item",
  });

export const removeCartItem = (orderItemId) =>
  request(`/cart/items/${orderItemId}`, {
    method: "DELETE",
    fallbackMessage: "Fail to remove shopping cart item",
  });

// Callers pass the same key when retrying the same payment so the backend can
// replay the stored result instead of charging twice.
export const checkoutWithPayment = (paymentDetails, idempotencyKey = createIdempotencyKey()) =>
  request("/payments/checkout", {
    method: "POST",
    headers: { ...jsonHeaders, "Idempotency-Key": idempotencyKey },
    body: JSON.stringify(paymentDetails),
    fallbackMessage: "Fail to complete payment",
  });

export const getOrders = ({ limit = 50, signal } = {}) =>
  request(`/orders?limit=${encodeURIComponent(limit)}`, {
    signal,
    fallbackMessage: "Fail to load your orders",
  });

export const cancelOrder = (orderId, idempotencyKey = createIdempotencyKey()) =>
  request(`/orders/${orderId}/cancel`, {
    method: "POST",
    headers: { "Idempotency-Key": idempotencyKey },
    fallbackMessage: "Fail to cancel the order",
  });

export const getNotifications = ({ limit = 50, signal } = {}) =>
  request(`/notifications?limit=${encodeURIComponent(limit)}`, {
    signal,
    fallbackMessage: "Fail to load notifications",
  });

export const updateOrderStatus = (orderId, status, idempotencyKey = createIdempotencyKey()) =>
  request(`/orders/${orderId}/status`, {
    method: "PATCH",
    headers: { ...jsonHeaders, "Idempotency-Key": idempotencyKey },
    body: JSON.stringify({ status }),
    fallbackMessage: "Fail to update order status",
  });

export const replayDeadLetter = (deadLetterEventId) =>
  request(`/dead-letters/${deadLetterEventId}/replay`, {
    method: "POST",
    fallbackMessage: "Fail to replay the dead-letter event",
  });
