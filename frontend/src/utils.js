export class ApiError extends Error {
  constructor(message, { status, traceId, body } = {}) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.traceId = traceId;
    this.body = body;
  }
}

const statusMessages = {
  400: "The request is invalid. Check the fields and try again.",
  401: "Sign in to continue.",
  403: "This account is not allowed to perform that action.",
  404: "The requested record was not found.",
  409: "The server rejected this state change. Refresh and try again.",
  429: "Too many requests. Wait a moment and try again.",
  500: "The server failed to complete the request.",
};

const parseErrorMessage = (body, fallbackMessage, status) => {
  if (!body) {
    return statusMessages[status] || fallbackMessage;
  }

  try {
    const parsed = JSON.parse(body);
    return parsed.message || parsed.error || statusMessages[status] || fallbackMessage;
  } catch {
    return body || statusMessages[status] || fallbackMessage;
  }
};

const request = async (url, options = {}, fallbackMessage = "Request failed") => {
  const response = await fetch(url, {
    credentials: "include",
    ...options,
    headers: options.headers || undefined,
  });
  const traceId = response.headers.get("X-Trace-Id");

  if (response.status < 200 || response.status >= 300) {
    const body = await response.text();
    throw new ApiError(parseErrorMessage(body, fallbackMessage, response.status), {
      status: response.status,
      traceId,
      body,
    });
  }

  const contentType = response.headers.get("content-type");

  if (contentType && contentType.includes("application/json")) {
    return response.json();
  }

  return null;
};

export const createIdempotencyKey = () => {
  const cryptoApi = typeof window !== "undefined" ? window.crypto : undefined;

  if (cryptoApi?.randomUUID) {
    return cryptoApi.randomUUID();
  }

  return `req-${Date.now()}-${Math.random().toString(16).slice(2)}`;
};

export const login = (credentials) => {
  const payload = new URLSearchParams({
    username: credentials.username,
    password: credentials.password,
  });

  return request(
    "/login",
    {
      method: "POST",
      headers: {
        "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8",
      },
      body: payload.toString(),
    },
    "Fail to log in"
  );
};

export const logout = () =>
  request("/logout", { method: "POST" }, "Fail to log out");

export const signup = (data) =>
  request(
    "/signup",
    {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
      },
      body: JSON.stringify(data),
    },
    "Fail to sign up"
  );

export const getCurrentUser = () =>
  request("/me", undefined, "Fail to load your session");

export const getMenus = (restaurantId) =>
  request(`/restaurant/${restaurantId}/menu`, undefined, "Fail to get menus");

export const getRestaurants = () =>
  request("/restaurants/menu", undefined, "Fail to get restaurants");

export const getCart = () =>
  request("/cart", undefined, "Fail to get shopping cart data");

export const checkout = (idempotencyKey = createIdempotencyKey()) =>
  request(
    "/cart/checkout",
    {
      method: "POST",
      headers: {
        "Idempotency-Key": idempotencyKey,
      },
    },
    "Fail to checkout"
  );

export const checkoutWithPayment = (
  paymentDetails,
  idempotencyKey = createIdempotencyKey()
) =>
  request(
    "/payments/checkout",
    {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Idempotency-Key": idempotencyKey,
      },
      body: JSON.stringify(paymentDetails),
    },
    "Fail to complete payment"
  );

export const addItemToCart = (itemId) =>
  request(
    "/cart",
    {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ menu_id: itemId }),
    },
    "Fail to add menu item to shopping cart"
  );

export const updateCartItem = (orderItemId, quantity) =>
  request(
    `/cart/items/${orderItemId}`,
    {
      method: "PUT",
      headers: {
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ quantity }),
    },
    "Fail to update shopping cart item"
  );

export const removeCartItem = (orderItemId) =>
  request(
    `/cart/items/${orderItemId}`,
    {
      method: "DELETE",
    },
    "Fail to remove shopping cart item"
  );

export const getOrders = () =>
  request("/orders", undefined, "Fail to load your orders");

export const cancelOrder = (orderId, idempotencyKey = createIdempotencyKey()) =>
  request(
    `/orders/${orderId}/cancel`,
    {
      method: "POST",
      headers: {
        "Idempotency-Key": idempotencyKey,
      },
    },
    "Fail to cancel the order"
  );

export const getNotifications = () =>
  request("/notifications", undefined, "Fail to load notifications");

export const updateOrderStatus = (
  orderId,
  status,
  idempotencyKey = createIdempotencyKey()
) =>
  request(
    `/orders/${orderId}/status`,
    {
      method: "PATCH",
      headers: {
        "Content-Type": "application/json",
        "Idempotency-Key": idempotencyKey,
      },
      body: JSON.stringify({ status }),
    },
    "Fail to update order status"
  );

export const replayDeadLetter = (deadLetterEventId) =>
  request(
    `/dead-letters/${deadLetterEventId}/replay`,
    {
      method: "POST",
    },
    "Fail to replay the dead-letter event"
  );
