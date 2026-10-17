import { afterEach, describe, expect, it, vi } from "vitest";
import { json } from "../test/fakeBackend";
import { ApiError, createIdempotencyKey, hasErrorCode, onUnauthorized, request } from "./client";
import * as api from "./endpoints";
import { API_PROXY_PATTERN } from "./proxyPaths";

const orderJson = { id: 101, status: "PAID", total_price: 9, items: [] };

const stubFetch = (
  implementation: () => Promise<Response> = () => Promise.resolve(json(orderJson)),
) => {
  const fetchMock = vi.fn((_input: RequestInfo | URL, _init?: RequestInit) => implementation());
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
};

const payment = {
  cardholder_name: "Demo User",
  card_number: "4242424242424242",
  expiry: "12/30",
  cvv: "123",
};

const merchantOrderJson = {
  order_id: 7,
  restaurant_id: 1,
  customer_id: 2,
  status: "ACCEPTED",
  total_price: 12.78,
  items: [{ menu_item_id: 10, menu_item_name: "Whopper", price: 6.39, quantity: 2 }],
};
const riderJson = { rider_id: 3, name: "Alice", active: true, on_duty: true, active_deliveries: 0 };
const merchantAccountJson = {
  merchant_id: 3,
  customer_id: 21,
  email: "m@b.c",
  restaurant_id: 1,
  active: true,
};
const riderAccountJson = {
  rider_id: 4,
  name: "Rider",
  active: true,
  on_duty: false,
  active_deliveries: 0,
};
const deliveryJson = {
  delivery_id: 5,
  order_id: 7,
  restaurant_id: 1,
  restaurant_name: "Burger Barn",
  status: "PICKED_UP",
};

describe("API endpoints", () => {
  it("use the backend-supported endpoints and idempotency headers", async () => {
    const fetchMock = stubFetch();

    await api.checkoutWithPayment(payment, "pay-key-1");
    await api.cancelOrder(101, "cancel-key-1");
    await api.updateOrderStatus(101, "ACCEPTED", "status-key-1");
    fetchMock.mockImplementation(() => Promise.resolve(json([])));
    await api.getOrders();
    await api.getNotifications();
    fetchMock.mockImplementation(() => Promise.resolve(json({ id: 7, replay_status: "REPLAYED" })));
    await api.replayDeadLetter(7);

    expect(fetchMock).toHaveBeenNthCalledWith(
      1,
      "/payments/checkout",
      expect.objectContaining({
        method: "POST",
        credentials: "include",
        headers: expect.objectContaining({
          "Content-Type": "application/json",
          "Idempotency-Key": "pay-key-1",
        }),
      }),
    );
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      "/orders/101/cancel",
      expect.objectContaining({
        method: "POST",
        credentials: "include",
        headers: { "Idempotency-Key": "cancel-key-1" },
      }),
    );
    expect(fetchMock).toHaveBeenNthCalledWith(
      3,
      "/orders/101/status",
      expect.objectContaining({
        method: "PATCH",
        credentials: "include",
        headers: expect.objectContaining({
          "Content-Type": "application/json",
          "Idempotency-Key": "status-key-1",
        }),
        body: JSON.stringify({ status: "ACCEPTED" }),
      }),
    );
    expect(fetchMock).toHaveBeenNthCalledWith(
      4,
      "/orders?limit=50",
      expect.objectContaining({ credentials: "include" }),
    );
    expect(fetchMock).toHaveBeenNthCalledWith(
      5,
      "/notifications?limit=50",
      expect.objectContaining({ credentials: "include" }),
    );
    expect(fetchMock).toHaveBeenNthCalledWith(
      6,
      "/dead-letters/7/replay",
      expect.objectContaining({ method: "POST", credentials: "include" }),
    );
    expect(createIdempotencyKey()).toEqual(expect.any(String));
  });

  it("forward the caller's AbortSignal to fetch", async () => {
    const fetchMock = stubFetch(() => Promise.resolve(json([])));
    const controller = new AbortController();

    await api.getMenus(3, { signal: controller.signal });
    fetchMock.mockImplementation(() => Promise.resolve(json({ total_price: 0, order_items: [] })));
    await api.getCart({ signal: controller.signal });

    expect(fetchMock).toHaveBeenNthCalledWith(
      1,
      "/restaurant/3/menu",
      expect.objectContaining({ signal: controller.signal }),
    );
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      "/cart",
      expect.objectContaining({ signal: controller.signal }),
    );
  });

  it("are all covered by the dev-server proxy, and app routes are not", async () => {
    // Every endpoint function, each answered with a body its decoder accepts.
    const calls: [() => Promise<unknown>, unknown][] = [
      [() => api.login({ username: "a@b.c", password: "x" }), null],
      [() => api.logout(), null],
      [() => api.signup({ email: "a@b.c", password: "x", first_name: "A", last_name: "B" }), null],
      [() => api.getCurrentUser(), { id: 1, email: "a@b.c" }],
      [() => api.getRestaurants(), []],
      [() => api.getMenus(1), []],
      [() => api.getCart(), { total_price: 0, order_items: [] }],
      [() => api.addItemToCart(1), null],
      [() => api.updateCartItem(1, 2), null],
      [() => api.removeCartItem(1), null],
      [() => api.checkoutWithPayment(payment, "k"), orderJson],
      [() => api.getOrders(), []],
      [() => api.cancelOrder(1, "k"), orderJson],
      [() => api.getNotifications(), []],
      [() => api.updateOrderStatus(1, "PAID", "k"), orderJson],
      [() => api.replayDeadLetter(1), { id: 1, replay_status: "REPLAYED" }],
      [() => api.clearCart(), null],
      [() => api.getOrderHistory(1), []],
      [() => api.getMerchantOrders({ status: "PAID" }), []],
      [() => api.acceptMerchantOrder(7), merchantOrderJson],
      [() => api.rejectMerchantOrder(7, "Closed"), merchantOrderJson],
      [() => api.markMerchantOrderPreparing(7), merchantOrderJson],
      [() => api.getRiderProfile(), riderJson],
      [() => api.updateRiderAvailability(true), riderJson],
      [() => api.getRiderDeliveries({ status: "DELIVERED" }), []],
      [() => api.markDeliveryPickedUp(5), deliveryJson],
      [() => api.markDeliveryDelivered(5), deliveryJson],
      [() => api.getMerchantAccounts(), [merchantAccountJson]],
      [
        () =>
          api.createMerchantAccount({
            email: "m@b.c",
            password: "x",
            first_name: "M",
            last_name: "B",
            restaurant_id: 1,
          }),
        merchantAccountJson,
      ],
      [() => api.setMerchantAccountActive(3, false), merchantAccountJson],
      [() => api.getRiderAccounts(), [riderAccountJson]],
      [
        () =>
          api.createRiderAccount({
            email: "r@b.c",
            password: "x",
            first_name: "R",
            last_name: "B",
            phone: "1",
          }),
        riderAccountJson,
      ],
      [() => api.setRiderAccountActive(4, true), riderAccountJson],
    ];
    const fetchMock = stubFetch();
    for (const [call, body] of calls) {
      fetchMock.mockImplementationOnce(() =>
        Promise.resolve(body === null ? new Response(null, { status: 200 }) : json(body)),
      );
      await call();
    }

    const proxied = new RegExp(API_PROXY_PATTERN);
    const urls = fetchMock.mock.calls.map(([url]) => url);
    expect(urls).toHaveLength(calls.length);
    urls.forEach((url) => expect(url).toMatch(proxied));
    [
      "/",
      "/index.html",
      "/src/main.tsx",
      "/@vite/client",
      "/static/index.js",
      "/menu.png",
      "/carts",
    ].forEach((path) => expect(path).not.toMatch(proxied));
  });
});

describe("role endpoints", () => {
  it("send the shared contract's methods, paths and bodies", async () => {
    const fetchMock = stubFetch(() => Promise.resolve(json(merchantOrderJson)));

    await api.rejectMerchantOrder(7, "Out of buns");
    fetchMock.mockImplementation(() => Promise.resolve(json(riderJson)));
    await api.updateRiderAvailability(false);
    fetchMock.mockImplementation(() => Promise.resolve(json([])));
    await api.getMerchantOrders();
    await api.getRiderDeliveries();
    fetchMock.mockImplementation(() => Promise.resolve(new Response(null, { status: 204 })));
    await api.clearCart();
    fetchMock.mockImplementation(() => Promise.resolve(json(merchantAccountJson)));
    await api.setMerchantAccountActive(3, false);
    fetchMock.mockImplementation(() => Promise.resolve(json(riderAccountJson)));
    await api.setRiderAccountActive(4, true);

    expect(fetchMock).toHaveBeenNthCalledWith(
      1,
      "/merchant/orders/7/reject",
      expect.objectContaining({ method: "POST", body: JSON.stringify({ reason: "Out of buns" }) }),
    );
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      "/rider/me/availability",
      expect.objectContaining({ method: "PUT", body: JSON.stringify({ on_duty: false }) }),
    );
    expect(fetchMock).toHaveBeenNthCalledWith(3, "/merchant/orders?limit=50", expect.anything());
    expect(fetchMock).toHaveBeenNthCalledWith(4, "/rider/deliveries", expect.anything());
    expect(fetchMock).toHaveBeenNthCalledWith(
      5,
      "/cart",
      expect.objectContaining({ method: "DELETE" }),
    );
    expect(fetchMock).toHaveBeenNthCalledWith(
      6,
      "/admin/merchants/3",
      expect.objectContaining({ method: "PATCH", body: JSON.stringify({ active: false }) }),
    );
    expect(fetchMock).toHaveBeenNthCalledWith(
      7,
      "/admin/riders/4",
      expect.objectContaining({ method: "PATCH", body: JSON.stringify({ active: true }) }),
    );
  });

  it("decode /me with roles, merchant and rider, and without them", async () => {
    stubFetch(() =>
      Promise.resolve(
        json({
          id: 21,
          email: "merchant@laifood.com",
          roles: ["ROLE_MERCHANT"],
          merchant: {
            merchant_id: 3,
            restaurant_id: 1,
            restaurant_name: "Burger Barn",
            active: true,
          },
        }),
      ),
    );
    await expect(api.getCurrentUser()).resolves.toEqual({
      id: 21,
      email: "merchant@laifood.com",
      first_name: null,
      last_name: null,
      roles: ["ROLE_MERCHANT"],
      merchant: { merchant_id: 3, restaurant_id: 1, restaurant_name: "Burger Barn", active: true },
      rider: null,
    });

    stubFetch(() => Promise.resolve(json({ id: 1, email: "demo@laifood.com" })));
    await expect(api.getCurrentUser()).resolves.toMatchObject({ roles: [], merchant: null });

    stubFetch(() => Promise.resolve(json({ id: 1, email: "demo@laifood.com", roles: "ADMIN" })));
    await expect(api.getCurrentUser()).rejects.toThrow(
      'Unexpected response from the server: me.roles should be an array, got the string "ADMIN".',
    );
  });

  it("decode the currency next to every amount", async () => {
    stubFetch(() =>
      Promise.resolve(
        json([
          {
            ...orderJson,
            total_price: 0.5,
            currency: "EUR",
            items: [
              {
                id: 1,
                menu_item_id: 30,
                price: 0.1,
                currency: "EUR",
                quantity: 5,
                menu_item_name: "Fish Taco",
              },
            ],
          },
        ]),
      ),
    );
    await expect(api.getOrders()).resolves.toMatchObject([
      { total_price: 0.5, currency: "EUR", items: [{ price: 0.1, currency: "EUR" }] },
    ]);

    // A backend from before currencies sends none: every amount was USD then.
    stubFetch(() => Promise.resolve(json([merchantOrderJson])));
    await expect(api.getMerchantOrders()).resolves.toMatchObject([
      { total_price: 12.78, currency: "USD", items: [{ price: 6.39, currency: "USD" }] },
    ]);

    stubFetch(() => Promise.resolve(json([{ ...orderJson, currency: "eur" }])));
    await expect(api.getOrders()).rejects.toMatchObject({
      name: "DecodeError",
      path: "orders[0].currency",
      message:
        'Unexpected response from the server: orders[0].currency should be an ISO 4217 currency code, got the string "eur".',
    });
  });

  it("reject statuses outside the contract", async () => {
    stubFetch(() => Promise.resolve(json([{ ...deliveryJson, status: "LOST" }])));

    await expect(api.getRiderDeliveries()).rejects.toMatchObject({
      name: "DecodeError",
      path: "deliveries[0].status",
    });
  });
});

describe("request", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("turns error responses into ApiError with status, code, message and trace id", async () => {
    stubFetch(() =>
      Promise.resolve(
        json(
          { code: "NOT_FOUND", message: "Cart item not found" },
          { status: 404, headers: { "X-Trace-Id": "trace-9" } },
        ),
      ),
    );

    const failure: unknown = await request("/cart/items/9").catch((error: unknown) => error);
    expect(failure).toMatchObject({
      name: "ApiError",
      status: 404,
      code: "NOT_FOUND",
      message: "Cart item not found",
      traceId: "trace-9",
    });
    expect(hasErrorCode(failure, "NOT_FOUND")).toBe(true);
    expect(hasErrorCode(failure, "CART_RESTAURANT_CONFLICT")).toBe(false);
  });

  it("falls back to the trace id in the error body and a status message", async () => {
    stubFetch(() =>
      Promise.resolve(json({ code: "ERROR", trace_id: "trace-body" }, { status: 429 })),
    );

    await expect(request("/cart")).rejects.toMatchObject({
      status: 429,
      message: "Too many requests. Wait a moment and try again.",
      traceId: "trace-body",
    });
  });

  it("reports a 401 from a protected endpoint to unauthorized listeners", async () => {
    stubFetch(() => Promise.resolve(json({ message: "Authentication required" }, { status: 401 })));
    const listener = vi.fn();
    const unsubscribe = onUnauthorized(listener);

    await expect(api.getCart()).rejects.toBeInstanceOf(ApiError);
    expect(listener).toHaveBeenCalledTimes(1);
    expect(listener).toHaveBeenCalledWith(expect.objectContaining({ status: 401 }));

    unsubscribe();
    await expect(api.getOrders()).rejects.toBeInstanceOf(ApiError);
    expect(listener).toHaveBeenCalledTimes(1);
  });

  it("does not treat a rejected login or a guest /me probe as an expired session", async () => {
    stubFetch(() =>
      Promise.resolve(json({ message: "Invalid email or password" }, { status: 401 })),
    );
    const listener = vi.fn();
    const unsubscribe = onUnauthorized(listener);

    await expect(
      api.login({ username: "demo@laifood.com", password: "wrong" }),
    ).rejects.toMatchObject({
      status: 401,
      message: "Invalid email or password",
    });
    await expect(api.getCurrentUser()).rejects.toMatchObject({ status: 401 });

    expect(listener).not.toHaveBeenCalled();
    unsubscribe();
  });

  it("wraps network failures but lets aborts through untouched", async () => {
    stubFetch(() => Promise.reject(new TypeError("Failed to fetch")));
    await expect(api.getRestaurants()).rejects.toMatchObject({
      name: "ApiError",
      status: undefined,
      message: "Unable to reach the server. Check the connection and try again.",
    });

    const abortError = new DOMException("The operation was aborted.", "AbortError");
    stubFetch(() => Promise.reject(abortError));
    await expect(api.getRestaurants()).rejects.toBe(abortError);
  });
});
