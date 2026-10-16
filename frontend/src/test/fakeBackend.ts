import { type Mock, vi } from "vitest";
import { fieldsOf } from "../api/decode";
import type {
  Cart,
  CartItem,
  CurrentUser,
  MenuItem,
  Order,
  OrderNotification,
  Restaurant,
} from "../api/dto";
import { parseOrderStatus } from "../api/dto";

export const json = (body: unknown, init: ResponseInit = {}): Response =>
  new Response(JSON.stringify(body), {
    status: 200,
    headers: { "Content-Type": "application/json" },
    ...init,
  });

export const apiError = (
  status: number,
  message: string,
  { traceId, code = "ERROR" }: { traceId?: string; code?: string } = {},
): Response =>
  json(
    { code, message, status, trace_id: traceId },
    { status, headers: { "Content-Type": "application/json" } },
  );

export interface Deferred<T> {
  promise: Promise<T>;
  resolve: (value: T) => void;
  reject: (reason: unknown) => void;
}

export const deferred = <T = Response>(): Deferred<T> => {
  let resolve: (value: T) => void = () => undefined;
  let reject: (reason: unknown) => void = () => undefined;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
};

export const demoUser: CurrentUser = {
  id: 1,
  email: "demo@laifood.com",
  first_name: "Demo",
  last_name: "User",
};

export const restaurantsFixture: Restaurant[] = [
  {
    id: 1,
    name: "Burger Barn",
    address: "773 N Mathilda Ave",
    phone: "(408) 736-0101",
    image_url: "",
  },
  { id: 2, name: "Noodle Nook", address: "1 Market St", phone: "", image_url: "" },
  { id: 3, name: "Taco Town", address: "2 Mission St", phone: "", image_url: "" },
];

export const menusFixture: Readonly<Record<number, MenuItem[]>> = {
  1: [
    {
      id: 10,
      restaurant_id: 1,
      name: "Whopper",
      description: "Flame grilled burger",
      price: 6.39,
      image_url: "",
    },
  ],
  2: [
    {
      id: 20,
      restaurant_id: 2,
      name: "Dan Dan Noodles",
      description: "",
      price: 11,
      image_url: "",
    },
  ],
  3: [{ id: 30, restaurant_id: 3, name: "Fish Taco", description: "", price: 4.5, image_url: "" }],
};

const menuItemById = (id: number): MenuItem | undefined =>
  Object.values(menusFixture)
    .flat()
    .find((item) => item.id === id);

const recomputeTotal = (cart: Cart) => {
  cart.total_price = cart.order_items.reduce((sum, item) => sum + item.price * item.quantity, 0);
};

/** One request the fake backend received. */
export interface FakeRequest {
  url: string;
  path: string;
  query: URLSearchParams;
  method: string;
  body: string | null;
  headers: Headers;
  signal: AbortSignal | null | undefined;
}

export type FakeHandler = (
  request: FakeRequest,
  match: RegExpExecArray,
) => Response | Promise<Response>;

/** The JSON body of a recorded request, as `unknown` for assertions and decoders. */
export const requestJson = (request: FakeRequest | undefined): unknown => {
  const parsed: unknown = JSON.parse(request?.body ?? "null");
  return parsed;
};

/** The Idempotency-Key header of each request. */
export const idempotencyKeys = (requests: FakeRequest[]): (string | null)[] =>
  requests.map((request) => request.headers.get("Idempotency-Key"));

const idParam = (match: RegExpExecArray) => Number(match[1]);

const requestUrl = (input: RequestInfo | URL): string => {
  if (typeof input === "string") {
    return input;
  }
  return input instanceof URL ? `${input.pathname}${input.search}` : input.url;
};

interface FakeBackendOptions {
  user?: CurrentUser | null;
  cartItems?: CartItem[];
}

export interface FakeState {
  user: CurrentUser | null;
  cart: Cart;
  orders: Order[];
  notifications: OrderNotification[];
  nextOrderItemId: number;
  nextOrderId: number;
}

/** A placed order with defaults for the fields a test does not care about. */
export const orderFixture = (overrides: Partial<Order> & Pick<Order, "id">): Order => ({
  status: "PAID",
  total_price: 9,
  created_at: null,
  items: [],
  restaurant_id: 1,
  cancel_reason: null,
  ...overrides,
});

/**
 * In-memory stand-in for the Spring Boot API, installed as global fetch. Protected
 * endpoints answer 401 when no user is signed in, like the real security config.
 * `on(method, path, handler)` overrides one route (once by default) so a test can
 * delay or fail a specific request.
 */
export function createFakeBackend({ user = null, cartItems = [] }: FakeBackendOptions = {}) {
  const state: FakeState = {
    user,
    cart: { order_items: cartItems.map((item) => ({ ...item })), total_price: 0 },
    orders: [],
    notifications: [],
    nextOrderItemId: 500,
    nextOrderId: 100,
  };
  recomputeTotal(state.cart);

  const overrides: { method: string; pattern: RegExp; handler: FakeHandler; once: boolean }[] = [];
  const calls: FakeRequest[] = [];

  const unauthorized = () => apiError(401, "Authentication required");
  const protectedRoute =
    (handler: FakeHandler): FakeHandler =>
    (request, match) =>
      state.user ? handler(request, match) : unauthorized();

  const routes: [string, RegExp, FakeHandler][] = [
    ["GET", /^\/me$/, () => (state.user ? json(state.user) : unauthorized())],
    [
      "POST",
      /^\/login$/,
      ({ body }) => {
        const form = new URLSearchParams(body ?? "");
        if (form.get("username") === demoUser.email && form.get("password") === "demo123") {
          state.user = demoUser;
          return new Response(null, { status: 200 });
        }
        return apiError(401, "Invalid email or password");
      },
    ],
    [
      "POST",
      /^\/logout$/,
      () => {
        state.user = null;
        return new Response(null, { status: 200 });
      },
    ],
    ["POST", /^\/signup$/, () => new Response(null, { status: 201 })],
    ["GET", /^\/restaurants\/menu$/, () => json(restaurantsFixture)],
    [
      "GET",
      /^\/restaurant\/(\d+)\/menu$/,
      (_request, match) => json(menusFixture[idParam(match)] ?? []),
    ],
    ["GET", /^\/cart$/, protectedRoute(() => json(state.cart))],
    [
      "POST",
      /^\/cart$/,
      protectedRoute((request) => {
        const menuId = fieldsOf(requestJson(request), "body").number("menu_id");
        const menuItem = menuItemById(menuId);
        if (!menuItem) {
          return apiError(404, "Menu item not found");
        }
        if (state.cart.order_items.some((item) => item.restaurant_id !== menuItem.restaurant_id)) {
          return apiError(409, "Your cart already has items from another restaurant.", {
            code: "CART_RESTAURANT_CONFLICT",
          });
        }
        const existing = state.cart.order_items.find((item) => item.menu_item_id === menuId);
        if (existing) {
          existing.quantity += 1;
        } else {
          state.cart.order_items.push({
            order_item_id: state.nextOrderItemId++,
            menu_item_id: menuId,
            restaurant_id: menuItem.restaurant_id,
            menu_item_name: menuItem.name,
            menu_item_description: menuItem.description,
            menu_item_image_url: "",
            price: menuItem.price,
            quantity: 1,
          });
        }
        recomputeTotal(state.cart);
        return new Response(null, { status: 200 });
      }),
    ],
    [
      "DELETE",
      /^\/cart$/,
      protectedRoute(() => {
        state.cart.order_items = [];
        recomputeTotal(state.cart);
        return new Response(null, { status: 204 });
      }),
    ],
    [
      "PUT",
      /^\/cart\/items\/(\d+)$/,
      protectedRoute((request, match) => {
        const line = state.cart.order_items.find((item) => item.order_item_id === idParam(match));
        if (!line) {
          return apiError(404, "Cart item not found");
        }
        line.quantity = fieldsOf(requestJson(request), "body").number("quantity");
        state.cart.order_items = state.cart.order_items.filter((item) => item.quantity > 0);
        recomputeTotal(state.cart);
        return new Response(null, { status: 200 });
      }),
    ],
    [
      "DELETE",
      /^\/cart\/items\/(\d+)$/,
      protectedRoute((_request, match) => {
        state.cart.order_items = state.cart.order_items.filter(
          (item) => item.order_item_id !== idParam(match),
        );
        recomputeTotal(state.cart);
        return new Response(null, { status: 204 });
      }),
    ],
    [
      "POST",
      /^\/payments\/checkout$/,
      protectedRoute(() => {
        const order: Order = {
          id: state.nextOrderId++,
          status: "PAID",
          total_price: state.cart.total_price,
          created_at: "2026-10-16T12:00:00Z",
          restaurant_id: state.cart.order_items[0]?.restaurant_id ?? null,
          cancel_reason: null,
          items: state.cart.order_items.map((item) => ({
            id: item.order_item_id,
            menu_item_id: item.menu_item_id,
            restaurant_id: item.restaurant_id,
            menu_item_name: item.menu_item_name,
            menu_item_description: item.menu_item_description,
            menu_item_image_url: item.menu_item_image_url,
            price: item.price,
            quantity: item.quantity,
          })),
        };
        state.orders.unshift(order);
        state.notifications.unshift({
          id: order.id,
          order_id: order.id,
          event_type: "ORDER_PAID",
          title: `Order #${order.id} paid`,
          message: "Payment captured.",
          created_at: order.created_at,
        });
        state.cart.order_items = [];
        recomputeTotal(state.cart);
        return json(order, { status: 201 });
      }),
    ],
    ["GET", /^\/orders$/, protectedRoute(() => json(state.orders))],
    [
      "POST",
      /^\/orders\/(\d+)\/cancel$/,
      protectedRoute((_request, match) => {
        const order = state.orders.find((item) => item.id === idParam(match));
        if (!order) {
          return apiError(404, "Order not found");
        }
        order.status = "CANCELLED";
        return json({ ...order });
      }),
    ],
    [
      "PATCH",
      /^\/orders\/(\d+)\/status$/,
      protectedRoute((request, match) => {
        const order = state.orders.find((item) => item.id === idParam(match));
        const status = parseOrderStatus(fieldsOf(requestJson(request), "body").string("status"));
        if (!order || !status) {
          return apiError(404, "Order not found");
        }
        order.status = status;
        return json({ ...order });
      }),
    ],
    ["GET", /^\/notifications$/, protectedRoute(() => json(state.notifications))],
  ];

  const fetchMock: Mock<(input: RequestInfo | URL, init?: RequestInit) => Promise<Response>> =
    vi.fn(async (input: RequestInfo | URL, init: RequestInit = {}) => {
      const url = requestUrl(input);
      const method = init.method ?? "GET";
      const [path = "", search = ""] = url.split("?");
      const request: FakeRequest = {
        url,
        path,
        query: new URLSearchParams(search),
        method,
        body: typeof init.body === "string" ? init.body : null,
        headers: new Headers(init.headers),
        signal: init.signal,
      };
      calls.push(request);

      const overrideIndex = overrides.findIndex(
        (override) => override.method === method && override.pattern.test(path),
      );
      const override = overrides[overrideIndex];
      if (override) {
        if (override.once) {
          overrides.splice(overrideIndex, 1);
        }
        const match = override.pattern.exec(path);
        if (match) {
          return override.handler(request, match);
        }
      }

      for (const [routeMethod, pattern, handler] of routes) {
        const match = routeMethod === method ? pattern.exec(path) : null;
        if (match) {
          return handler(request, match);
        }
      }
      throw new Error(`Fake backend has no route for ${method} ${url}`);
    });

  return {
    state,
    fetch: fetchMock,
    calls,
    callsTo: (method: string, path: string) =>
      calls.filter((call) => call.method === method && call.path === path),
    on(method: string, path: string | RegExp, handler: FakeHandler, { once = true } = {}) {
      const pattern =
        path instanceof RegExp
          ? path
          : new RegExp(`^${path.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}$`);
      overrides.push({ method, pattern, handler, once });
    },
  };
}

export type FakeBackend = ReturnType<typeof createFakeBackend>;

export function installFakeBackend(options?: FakeBackendOptions): FakeBackend {
  const backend = createFakeBackend(options);
  vi.stubGlobal("fetch", backend.fetch);
  return backend;
}
