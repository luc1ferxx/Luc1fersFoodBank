import { vi } from "vitest";

export const json = (body, init = {}) =>
  new Response(JSON.stringify(body), {
    status: 200,
    headers: { "Content-Type": "application/json" },
    ...init,
  });

export const apiError = (status, message, traceId) =>
  json(
    { code: "ERROR", message, status, trace_id: traceId },
    { status, headers: { "Content-Type": "application/json" } },
  );

export const deferred = () => {
  let resolve;
  let reject;
  const promise = new Promise((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
};

export const demoUser = {
  email: "demo@laifood.com",
  first_name: "Demo",
  last_name: "User",
};

export const restaurantsFixture = [
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

export const menusFixture = {
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

const menuItemById = (id) =>
  Object.values(menusFixture)
    .flat()
    .find((item) => item.id === id);

const recomputeTotal = (cart) => {
  cart.total_price = cart.order_items.reduce((sum, item) => sum + item.price * item.quantity, 0);
};

/**
 * In-memory stand-in for the Spring Boot API, installed as global fetch. Protected
 * endpoints answer 401 when no user is signed in, like the real security config.
 * `on(method, path, handler)` overrides one route (once by default) so a test can
 * delay or fail a specific request.
 */
export function createFakeBackend({ user = null, cartItems = [] } = {}) {
  const state = {
    user,
    cart: { order_items: cartItems.map((item) => ({ ...item })), total_price: 0 },
    orders: [],
    notifications: [],
    nextOrderItemId: 500,
    nextOrderId: 100,
  };
  recomputeTotal(state.cart);

  const overrides = [];
  const calls = [];

  const unauthorized = () => apiError(401, "Authentication required");
  const protectedRoute = (handler) => (request, match) =>
    state.user ? handler(request, match) : unauthorized();

  const routes = [
    ["GET", /^\/me$/, () => (state.user ? json(state.user) : unauthorized())],
    [
      "POST",
      /^\/login$/,
      ({ body }) => {
        const form = new URLSearchParams(body);
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
    ["GET", /^\/restaurant\/(\d+)\/menu$/, (request, match) => json(menusFixture[match[1]] || [])],
    ["GET", /^\/cart$/, protectedRoute(() => json(state.cart))],
    [
      "POST",
      /^\/cart$/,
      protectedRoute(({ body }) => {
        const { menu_id: menuId } = JSON.parse(body);
        const existing = state.cart.order_items.find((item) => item.menu_item_id === menuId);
        if (existing) {
          existing.quantity += 1;
        } else {
          const menuItem = menuItemById(menuId);
          state.cart.order_items.push({
            order_item_id: state.nextOrderItemId++,
            menu_item_id: menuId,
            menu_item_name: menuItem.name,
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
      "PUT",
      /^\/cart\/items\/(\d+)$/,
      protectedRoute(({ body }, match) => {
        const line = state.cart.order_items.find((item) => item.order_item_id === Number(match[1]));
        line.quantity = JSON.parse(body).quantity;
        state.cart.order_items = state.cart.order_items.filter((item) => item.quantity > 0);
        recomputeTotal(state.cart);
        return new Response(null, { status: 200 });
      }),
    ],
    [
      "DELETE",
      /^\/cart\/items\/(\d+)$/,
      protectedRoute((request, match) => {
        state.cart.order_items = state.cart.order_items.filter(
          (item) => item.order_item_id !== Number(match[1]),
        );
        recomputeTotal(state.cart);
        return new Response(null, { status: 204 });
      }),
    ],
    [
      "POST",
      /^\/payments\/checkout$/,
      protectedRoute(() => {
        const order = {
          id: state.nextOrderId++,
          status: "PAID",
          total_price: state.cart.total_price,
          created_at: "2026-10-16T12:00:00Z",
          items: state.cart.order_items.map((item) => ({
            id: item.order_item_id,
            menu_item_id: item.menu_item_id,
            menu_item_name: item.menu_item_name,
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
        state.cart = { order_items: [], total_price: 0 };
        return json(order, { status: 201 });
      }),
    ],
    ["GET", /^\/orders$/, protectedRoute(() => json(state.orders))],
    [
      "POST",
      /^\/orders\/(\d+)\/cancel$/,
      protectedRoute((request, match) => {
        const order = state.orders.find((item) => item.id === Number(match[1]));
        order.status = "CANCELLED";
        return json({ ...order });
      }),
    ],
    [
      "PATCH",
      /^\/orders\/(\d+)\/status$/,
      protectedRoute(({ body }, match) => {
        const order = state.orders.find((item) => item.id === Number(match[1]));
        if (!order) {
          return apiError(404, "Order not found");
        }
        order.status = JSON.parse(body).status;
        return json({ ...order });
      }),
    ],
    ["GET", /^\/notifications$/, protectedRoute(() => json(state.notifications))],
  ];

  const fetchMock = vi.fn(async (url, options = {}) => {
    const method = options.method || "GET";
    const [path] = url.split("?");
    const request = {
      url,
      path,
      method,
      body: options.body,
      headers: options.headers || {},
      signal: options.signal,
    };
    calls.push(request);

    const overrideIndex = overrides.findIndex(
      (override) => override.method === method && override.pattern.test(path),
    );
    if (overrideIndex >= 0) {
      const override = overrides[overrideIndex];
      if (override.once) {
        overrides.splice(overrideIndex, 1);
      }
      return override.handler(request, path.match(override.pattern));
    }

    const route = routes.find(
      ([routeMethod, pattern]) => routeMethod === method && pattern.test(path),
    );
    if (!route) {
      throw new Error(`Fake backend has no route for ${method} ${url}`);
    }
    return route[2](request, path.match(route[1]));
  });

  return {
    state,
    fetch: fetchMock,
    calls,
    callsTo: (method, path) => calls.filter((call) => call.method === method && call.path === path),
    on(method, path, handler, { once = true } = {}) {
      const pattern =
        path instanceof RegExp
          ? path
          : new RegExp(`^${path.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}$`);
      overrides.push({ method, pattern, handler, once });
    },
  };
}

export function installFakeBackend(options) {
  const backend = createFakeBackend(options);
  vi.stubGlobal("fetch", backend.fetch);
  return backend;
}
