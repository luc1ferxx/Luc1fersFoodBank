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
