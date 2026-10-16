import { createIdempotencyKey, request } from "./client";
import { decodeArray } from "./decode";
import {
  type Cart,
  type Credentials,
  type CurrentUser,
  type DeadLetterReplay,
  type MenuItem,
  type Order,
  type OrderNotification,
  type OrderStatus,
  type OrderStatusChange,
  type PaymentDetails,
  type Restaurant,
  type SignupRequest,
  decodeCart,
  decodeCurrentUser,
  decodeDeadLetterReplay,
  decodeMenuItem,
  decodeNotification,
  decodeOrder,
  decodeOrderStatusChange,
  decodeRestaurant,
} from "./dto";

export interface RequestControl {
  signal?: AbortSignal | undefined;
}

export interface ListControl extends RequestControl {
  limit?: number;
}

const jsonHeaders = { "Content-Type": "application/json" };

// ---- Session ----

export const login = async (
  { username, password }: Credentials,
  { signal }: RequestControl = {},
) => {
  await request("/login", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8" },
    body: new URLSearchParams({ username, password }).toString(),
    signal,
    fallbackMessage: "Fail to log in",
    reportUnauthorized: false,
  });
};

export const logout = async () => {
  await request("/logout", {
    method: "POST",
    fallbackMessage: "Fail to log out",
    reportUnauthorized: false,
  });
};

export const signup = async (data: SignupRequest) => {
  await request("/signup", {
    method: "POST",
    headers: jsonHeaders,
    body: JSON.stringify(data),
    fallbackMessage: "Fail to sign up",
  });
};

export const getCurrentUser = async ({ signal }: RequestControl = {}): Promise<CurrentUser> =>
  decodeCurrentUser(
    await request("/me", {
      signal,
      fallbackMessage: "Fail to load your session",
      reportUnauthorized: false,
    }),
    "me",
  );

// ---- Catalog ----

export const getRestaurants = async ({ signal }: RequestControl = {}): Promise<Restaurant[]> =>
  decodeArray(decodeRestaurant)(
    await request("/restaurants/menu", { signal, fallbackMessage: "Fail to get restaurants" }),
    "restaurants",
  );

export const getMenus = async (
  restaurantId: number,
  { signal }: RequestControl = {},
): Promise<MenuItem[]> =>
  decodeArray(decodeMenuItem)(
    await request(`/restaurant/${restaurantId}/menu`, {
      signal,
      fallbackMessage: "Fail to get menus",
    }),
    "menu",
  );

// ---- Cart ----

export const getCart = async ({ signal }: RequestControl = {}): Promise<Cart> =>
  decodeCart(
    await request("/cart", { signal, fallbackMessage: "Fail to get shopping cart data" }),
    "cart",
  );

/** Empties the signed-in customer's cart (204, idempotent). */
export const clearCart = async () => {
  await request("/cart", {
    method: "DELETE",
    fallbackMessage: "Fail to clear the shopping cart",
  });
};

/** Fails with 409 CART_RESTAURANT_CONFLICT when the cart holds another restaurant's items. */
export const addItemToCart = async (menuItemId: number) => {
  await request("/cart", {
    method: "POST",
    headers: jsonHeaders,
    body: JSON.stringify({ menu_id: menuItemId }),
    fallbackMessage: "Fail to add menu item to shopping cart",
  });
};

export const updateCartItem = async (orderItemId: number, quantity: number) => {
  await request(`/cart/items/${orderItemId}`, {
    method: "PUT",
    headers: jsonHeaders,
    body: JSON.stringify({ quantity }),
    fallbackMessage: "Fail to update shopping cart item",
  });
};

export const removeCartItem = async (orderItemId: number) => {
  await request(`/cart/items/${orderItemId}`, {
    method: "DELETE",
    fallbackMessage: "Fail to remove shopping cart item",
  });
};

// ---- Orders ----

// Callers pass the same key when retrying the same payment so the backend can
// replay the stored result instead of charging twice.
export const checkoutWithPayment = async (
  paymentDetails: PaymentDetails,
  idempotencyKey: string = createIdempotencyKey(),
): Promise<Order> =>
  decodeOrder(
    await request("/payments/checkout", {
      method: "POST",
      headers: { ...jsonHeaders, "Idempotency-Key": idempotencyKey },
      body: JSON.stringify(paymentDetails),
      fallbackMessage: "Fail to complete payment",
    }),
    "order",
  );

export const getOrders = async ({ limit = 50, signal }: ListControl = {}): Promise<Order[]> =>
  decodeArray(decodeOrder)(
    await request(`/orders?limit=${encodeURIComponent(limit)}`, {
      signal,
      fallbackMessage: "Fail to load your orders",
    }),
    "orders",
  );

export const cancelOrder = async (
  orderId: number,
  idempotencyKey: string = createIdempotencyKey(),
): Promise<Order> =>
  decodeOrder(
    await request(`/orders/${orderId}/cancel`, {
      method: "POST",
      headers: { "Idempotency-Key": idempotencyKey },
      fallbackMessage: "Fail to cancel the order",
    }),
    "order",
  );

/** The order's status changes, oldest first (the order's owner or an admin; others get 404). */
export const getOrderHistory = async (
  orderId: number,
  { signal }: RequestControl = {},
): Promise<OrderStatusChange[]> =>
  decodeArray(decodeOrderStatusChange)(
    await request(`/orders/${orderId}/history`, {
      signal,
      fallbackMessage: "Fail to load the order's status history",
    }),
    "history",
  );

export const getNotifications = async ({ limit = 50, signal }: ListControl = {}): Promise<
  OrderNotification[]
> =>
  decodeArray(decodeNotification)(
    await request(`/notifications?limit=${encodeURIComponent(limit)}`, {
      signal,
      fallbackMessage: "Fail to load notifications",
    }),
    "notifications",
  );

// ---- Admin ----

export const updateOrderStatus = async (
  orderId: number,
  status: OrderStatus,
  idempotencyKey: string = createIdempotencyKey(),
): Promise<Order> =>
  decodeOrder(
    await request(`/orders/${orderId}/status`, {
      method: "PATCH",
      headers: { ...jsonHeaders, "Idempotency-Key": idempotencyKey },
      body: JSON.stringify({ status }),
      fallbackMessage: "Fail to update order status",
    }),
    "order",
  );

export const replayDeadLetter = async (deadLetterEventId: number): Promise<DeadLetterReplay> =>
  decodeDeadLetterReplay(
    await request(`/dead-letters/${deadLetterEventId}/replay`, {
      method: "POST",
      fallbackMessage: "Fail to replay the dead-letter event",
    }),
    "dead_letter",
  );
