import { type Mock, vi } from "vitest";
import { fieldsOf } from "../api/decode";
import {
  type ActorType,
  type Cart,
  type CartItem,
  type CurrentUser,
  type MenuItem,
  type MerchantAccount,
  type MerchantOrder,
  type Order,
  type OrderNotification,
  type OrderStatusChange,
  ROLES,
  type RiderAccount,
  type RiderDelivery,
  type RiderProfile,
  parseOrderStatus,
} from "../api/dto";
import { adminRoutes } from "./fakeAdmin";
import { type FakeContext, merchantRoutes, riderRoutes } from "./fakeFulfilment";
import {
  type FakeHandler,
  type FakeRequest,
  type FakeRoute,
  apiError,
  idParam,
  json,
  noContent,
  requestJson,
} from "./fakeHttp";
import { PASSWORDS, menusFixture, restaurantsFixture, riderProfileFixture } from "./fixtures";

export {
  apiError,
  deferred,
  idempotencyKeys,
  json,
  requestJson,
  type FakeHandler,
  type FakeRequest,
} from "./fakeHttp";
export { deliveryFixture, merchantOrderFixture } from "./fakeFulfilment";
export { merchantAccountFixture, riderAccountFixture } from "./fakeAdmin";
export {
  customerUser,
  demoUser,
  menusFixture,
  merchantUser,
  orderFixture,
  restaurantsFixture,
  riderProfileFixture,
  riderUser,
} from "./fixtures";

const menuItemById = (id: number): MenuItem | undefined =>
  Object.values(menusFixture)
    .flat()
    .find((item) => item.id === id);

const recomputeTotal = (cart: Cart) => {
  cart.total_price = cart.order_items.reduce((sum, item) => sum + item.price * item.quantity, 0);
};

const requestUrl = (input: RequestInfo | URL): string => {
  if (typeof input === "string") {
    return input;
  }
  return input instanceof URL ? `${input.pathname}${input.search}` : input.url;
};

export interface FakeBackendOptions {
  user?: CurrentUser | null;
  cartItems?: CartItem[];
}

export interface FakeState {
  user: CurrentUser | null;
  cart: Cart;
  /** The signed-in customer's orders, newest first. */
  orders: Order[];
  /** Status changes per order id, oldest first. */
  orderHistory: Map<number, OrderStatusChange[]>;
  notifications: OrderNotification[];
  /** The signed-in merchant's restaurant inbox. */
  merchantOrders: MerchantOrder[];
  /** The signed-in rider; null makes /rider/** answer 403. */
  riderProfile: RiderProfile | null;
  /** The signed-in rider's deliveries. */
  deliveries: RiderDelivery[];
  /** Provisioned logins listed by /admin/merchants and /admin/riders. */
  merchantAccounts: MerchantAccount[];
  riderAccounts: RiderAccount[];
  nextAccountId: number;
  nextOrderItemId: number;
  nextOrderId: number;
}

/**
 * In-memory stand-in for the Spring Boot API, installed as global fetch. Protected
 * endpoints answer 401 when no user is signed in and 403 without the route's role,
 * like the real security config. `on(method, path, handler)` overrides one route
 * (once by default) so a test can delay or fail a specific request.
 */
export function createFakeBackend({ user = null, cartItems = [] }: FakeBackendOptions = {}) {
  const state: FakeState = {
    user,
    cart: { order_items: cartItems.map((item) => ({ ...item })), total_price: 0 },
    orders: [],
    orderHistory: new Map(),
    notifications: [],
    merchantOrders: [],
    riderProfile: user?.roles.includes(ROLES.rider) ? { ...riderProfileFixture } : null,
    deliveries: [],
    merchantAccounts: [],
    riderAccounts: [],
    nextAccountId: 40,
    nextOrderItemId: 500,
    nextOrderId: 100,
  };
  recomputeTotal(state.cart);

  const overrides: { method: string; pattern: RegExp; handler: FakeHandler; once: boolean }[] = [];
  const calls: FakeRequest[] = [];

  const unauthorized = () => apiError(401, "Authentication required", { code: "UNAUTHORIZED" });
  const protectedRoute =
    (handler: FakeHandler): FakeHandler =>
    (request, match) =>
      state.user ? handler(request, match) : unauthorized();
  const hasRole = (role: string) => state.user?.roles.includes(role) === true;

  const context: FakeContext = {
    state,
    requireRole: (role, handler) =>
      protectedRoute((request, match) =>
        hasRole(role)
          ? handler(request, match)
          : apiError(403, "Access denied", { code: "FORBIDDEN" }),
      ),
    recordStatusChange: (orderId, fromStatus, toStatus, actorType: ActorType, reason = null) => {
      const history = state.orderHistory.get(orderId) ?? [];
      history.push({
        from_status: fromStatus,
        to_status: toStatus,
        actor_type: actorType,
        actor_id: actorType === "SYSTEM" ? null : (state.user?.id ?? null),
        reason,
        occurred_at: `2026-10-16T12:0${history.length}:00Z`,
      });
      state.orderHistory.set(orderId, history);
    },
    emailRegistered: (email) => {
      const normalized = email.trim().toLowerCase();
      return [
        ...PASSWORDS.keys(),
        ...state.merchantAccounts.map((account) => account.email),
        ...state.riderAccounts.flatMap((account) => (account.email ? [account.email] : [])),
      ].some((known) => known.toLowerCase() === normalized);
    },
  };
  const { recordStatusChange } = context;

  const sessionRoutes: FakeRoute[] = [
    ["GET", /^\/me$/, () => (state.user ? json(state.user) : unauthorized())],
    [
      "POST",
      /^\/login$/,
      ({ body }) => {
        const form = new URLSearchParams(body ?? "");
        const login = PASSWORDS.get(form.get("username") ?? "");
        if (login && login[1] === form.get("password")) {
          state.user = login[0];
          if (login[0].roles.includes(ROLES.rider) && !state.riderProfile) {
            state.riderProfile = { ...riderProfileFixture };
          }
          return noContent(200);
        }
        return apiError(401, "Invalid email or password");
      },
    ],
    [
      "POST",
      /^\/logout$/,
      () => {
        state.user = null;
        return noContent(200);
      },
    ],
    ["POST", /^\/signup$/, () => noContent(201)],
  ];

  const customerRoutes: FakeRoute[] = [
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
        return noContent(200);
      }),
    ],
    [
      "DELETE",
      /^\/cart$/,
      protectedRoute(() => {
        state.cart.order_items = [];
        recomputeTotal(state.cart);
        return noContent();
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
        return noContent(200);
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
        return noContent();
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
        recordStatusChange(order.id, null, "PAID", "CUSTOMER");
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
        if (order.status !== "CANCELLED") {
          recordStatusChange(order.id, order.status, "CANCELLED", "CUSTOMER");
        }
        order.status = "CANCELLED";
        return json({ ...order });
      }),
    ],
    [
      "PATCH",
      /^\/orders\/(\d+)\/status$/,
      context.requireRole(ROLES.admin, (request, match) => {
        const order = state.orders.find((item) => item.id === idParam(match));
        const status = parseOrderStatus(fieldsOf(requestJson(request), "body").string("status"));
        if (!order || !status) {
          return apiError(404, "Order not found");
        }
        if (order.status !== status) {
          recordStatusChange(order.id, order.status, status, "ADMIN");
        }
        order.status = status;
        return json({ ...order });
      }),
    ],
    [
      "GET",
      /^\/orders\/(\d+)\/history$/,
      protectedRoute((_request, match) => {
        const orderId = idParam(match);
        const owned = state.orders.some((order) => order.id === orderId);
        const history = state.orderHistory.get(orderId);
        if (!owned && !(hasRole(ROLES.admin) && history)) {
          return apiError(404, "Order not found");
        }
        return json(history ?? []);
      }),
    ],
    ["GET", /^\/notifications$/, protectedRoute(() => json(state.notifications))],
    [
      "POST",
      /^\/dead-letters\/(\d+)\/replay$/,
      context.requireRole(ROLES.admin, (_request, match) =>
        json({ id: idParam(match), replay_status: "REPLAYED" }),
      ),
    ],
  ];

  const routes: FakeRoute[] = [
    ...sessionRoutes,
    ...customerRoutes,
    ...merchantRoutes(context),
    ...riderRoutes(context),
    ...adminRoutes(context),
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
