const handleResponse = async (response, fallbackMessage) => {
  if (response.status < 200 || response.status >= 300) {
    const messageText = await response.text();
    throw Error(messageText || fallbackMessage);
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

  return fetch("/login", {
    method: "POST",
    headers: {
      "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8",
    },
    body: payload.toString(),
  }).then((response) => handleResponse(response, "Fail to log in"));
};

export const logout = () => {
  return fetch("/logout", {
    method: "POST",
  }).then((response) => handleResponse(response, "Fail to log out"));
};

export const signup = (data) => {
  return fetch("/signup", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    body: JSON.stringify(data),
  }).then((response) => handleResponse(response, "Fail to sign up"));
};

export const getCurrentUser = () => {
  return fetch("/me").then((response) =>
    handleResponse(response, "Fail to load your session")
  );
};

export const getMenus = (restaurantId) => {
  return fetch(`/restaurant/${restaurantId}/menu`).then((response) =>
    handleResponse(response, "Fail to get menus")
  );
};

export const getRestaurants = () => {
  return fetch("/restaurants/menu").then((response) =>
    handleResponse(response, "Fail to get restaurants")
  );
};

export const getCart = () => {
  return fetch("/cart").then((response) =>
    handleResponse(response, "Fail to get shopping cart data")
  );
};

export const checkout = (idempotencyKey = createIdempotencyKey()) => {
  return fetch("/cart/checkout", {
    method: "POST",
    headers: {
      "Idempotency-Key": idempotencyKey,
    },
  }).then((response) => handleResponse(response, "Fail to checkout"));
};

export const checkoutWithPayment = (
  paymentDetails,
  idempotencyKey = createIdempotencyKey()
) => {
  return fetch("/payments/checkout", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "Idempotency-Key": idempotencyKey,
    },
    body: JSON.stringify(paymentDetails),
  }).then((response) =>
    handleResponse(response, "Fail to complete payment")
  );
};

export const addItemToCart = (itemId) => {
  return fetch("/cart", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    body: JSON.stringify({ menu_id: itemId }),
  }).then((response) =>
    handleResponse(response, "Fail to add menu item to shopping cart")
  );
};

export const updateCartItem = (orderItemId, quantity) => {
  return fetch(`/cart/items/${orderItemId}`, {
    method: "PUT",
    headers: {
      "Content-Type": "application/json",
    },
    body: JSON.stringify({ quantity }),
  }).then((response) =>
    handleResponse(response, "Fail to update shopping cart item")
  );
};

export const removeCartItem = (orderItemId) => {
  return fetch(`/cart/items/${orderItemId}`, {
    method: "DELETE",
  }).then((response) =>
    handleResponse(response, "Fail to remove shopping cart item")
  );
};

export const getOrders = () => {
  return fetch("/orders").then((response) =>
    handleResponse(response, "Fail to load your orders")
  );
};
