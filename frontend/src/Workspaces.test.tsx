import { act, screen, waitFor, within } from "@testing-library/react";
import type { UserEvent } from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { type CartItem, ROLES } from "./api/dto";
import { NOTIFICATION_POLL_MS } from "./hooks/useNotifications";
import { SESSION_EXPIRED_MESSAGE } from "./hooks/useSession";
import {
  apiError,
  customerUser,
  demoUser,
  idempotencyKeys,
  installFakeBackend,
  merchantOrderFixture,
  merchantUser,
  orderFixture,
  riderUser,
  USD,
} from "./test/fakeBackend";
import { flushPromises, renderApp } from "./test/helpers";

const switcher = () => screen.queryByRole("navigation", { name: "Workspaces" });

/** Clicks a workspace in the switcher. */
const openWorkspace = async (user: UserEvent, name: string) => {
  const nav = switcher();
  if (!nav) {
    throw new Error("No workspace switcher");
  }
  await user.click(within(nav).getByRole("button", { name }));
};

const pressedWorkspace = () =>
  within(switcher() ?? document.body)
    .getAllByRole("button")
    .filter((button) => button.getAttribute("aria-pressed") === "true")
    .map((button) => button.textContent);

const whopperLine: CartItem = {
  order_item_id: 501,
  menu_item_id: 10,
  restaurant_id: 1,
  menu_item_name: "Whopper",
  menu_item_description: "Flame grilled burger",
  menu_item_image_url: "",
  price: 6.39,
  currency: USD,
  quantity: 2,
};

const signIn = async (user: UserEvent, email: string, password: string) => {
  await user.type(await screen.findByLabelText("Email"), email);
  await user.type(screen.getByLabelText("Password"), password);
  await user.click(screen.getByRole("button", { name: "Sign in" }));
};

describe("role-based workspaces", () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it("keeps a plain customer on the ordering page without a switcher", async () => {
    const backend = installFakeBackend({ user: customerUser });
    renderApp();

    expect(await screen.findByText("Casey is signed in")).toBeInTheDocument();
    expect(await screen.findByRole("button", { name: "Cart (0)" })).toBeInTheDocument();
    expect(switcher()).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Admin tools" })).not.toBeInTheDocument();
    expect(backend.callsTo("GET", "/merchant/orders")).toHaveLength(0);
  });

  it("lets a customer who is an admin switch between ordering and the admin console", async () => {
    const backend = installFakeBackend({ user: demoUser });
    const user = renderApp();

    await screen.findByText("Demo is signed in");
    const nav = switcher();
    if (!nav) {
      throw new Error("No workspace switcher");
    }
    expect(
      within(nav)
        .getAllByRole("button")
        .map((button) => button.textContent),
    ).toEqual(["Order food", "Admin console"]);
    expect(within(nav).getByRole("button", { name: "Order food" })).toHaveAttribute(
      "aria-pressed",
      "true",
    );

    await user.click(within(nav).getByRole("button", { name: "Admin console" }));
    expect(await screen.findByRole("region", { name: "Admin console" })).toBeInTheDocument();
    expect(screen.queryByRole("region", { name: "Restaurant and menu browsing" })).toBeNull();

    // Back on the ordering page the data is read again (the page itself was kept).
    await user.click(within(nav).getByRole("button", { name: "Order food" }));
    expect(await screen.findByRole("button", { name: "Cart (0)" })).toBeInTheDocument();
    await waitFor(() => expect(backend.callsTo("GET", "/cart")).toHaveLength(2));
    await waitFor(() => expect(backend.callsTo("GET", "/orders")).toHaveLength(2));
  });

  it("takes a merchant from the login form to the merchant console and back out", async () => {
    const backend = installFakeBackend();
    backend.state.merchantOrders = [merchantOrderFixture({ order_id: 7 })];
    const user = renderApp();

    await user.type(await screen.findByLabelText("Email"), merchantUser.email);
    await user.type(screen.getByLabelText("Password"), "merchant123");
    await user.click(screen.getByRole("button", { name: "Sign in" }));

    expect(await screen.findByRole("heading", { name: "Burger Barn orders" })).toBeInTheDocument();
    expect(await screen.findByRole("article", { name: "Order #7" })).toBeInTheDocument();
    expect(switcher()).not.toBeInTheDocument();
    // A merchant-only login never loads a customer cart.
    expect(backend.callsTo("GET", "/cart")).toHaveLength(0);

    await user.click(screen.getByRole("button", { name: "Logout" }));
    expect(await screen.findByText("Signed out.")).toBeInTheDocument();
    expect(screen.queryByRole("region", { name: "Merchant console" })).toBeNull();
  });

  it("lands a rider on the rider console", async () => {
    installFakeBackend({ user: riderUser });
    renderApp();

    expect(await screen.findByRole("region", { name: "My deliveries" })).toBeInTheDocument();
    expect(await screen.findByRole("heading", { name: "Alice Rider" })).toBeInTheDocument();
    expect(switcher()).not.toBeInTheDocument();
  });

  it("opens the merchant console first for a user with several roles and switches views", async () => {
    installFakeBackend({
      user: {
        ...merchantUser,
        roles: [ROLES.user, ROLES.merchant, ROLES.rider],
        rider: { rider_id: 7, name: "Alice Rider", on_duty: false, active: true },
      },
    });
    const user = renderApp();

    expect(await screen.findByRole("region", { name: "Merchant console" })).toBeInTheDocument();
    const nav = switcher();
    if (!nav) {
      throw new Error("No workspace switcher");
    }
    expect(
      within(nav)
        .getAllByRole("button")
        .map((button) => button.textContent),
    ).toEqual(["Order food", "Merchant console", "Rider console"]);

    await user.click(within(nav).getByRole("button", { name: "Rider console" }));
    expect(await screen.findByRole("region", { name: "My deliveries" })).toBeInTheDocument();
    await user.click(within(nav).getByRole("button", { name: "Order food" }));
    expect(
      await screen.findByRole("region", { name: "Restaurant and menu browsing" }),
    ).toBeInTheDocument();
  });

  it("falls back to ordering when /me has no roles", async () => {
    const backend = installFakeBackend();
    backend.on(
      "GET",
      "/me",
      () =>
        new Response(JSON.stringify({ id: 1, email: "demo@laifood.com", first_name: "Demo" }), {
          headers: { "Content-Type": "application/json" },
        }),
    );
    backend.state.user = demoUser;
    renderApp();

    expect(await screen.findByText("Demo is signed in")).toBeInTheDocument();
    expect(await screen.findByRole("button", { name: "Cart (0)" })).toBeInTheDocument();
    expect(switcher()).not.toBeInTheDocument();
  });

  it("returns an expired merchant session to the sign-in form", async () => {
    const backend = installFakeBackend({ user: merchantUser });
    backend.on("GET", "/merchant/orders", () => apiError(401, "Authentication required"));
    renderApp();

    expect(await screen.findByRole("alert")).toHaveTextContent(SESSION_EXPIRED_MESSAGE);
    expect(screen.getByRole("button", { name: "Sign in" })).toBeInTheDocument();
    expect(screen.queryByRole("region", { name: "Merchant console" })).toBeNull();
  });

  it("keeps a failed checkout's Idempotency-Key and form across a workspace switch", async () => {
    const backend = installFakeBackend({ user: demoUser, cartItems: [whopperLine] });
    backend.on("POST", "/payments/checkout", () =>
      apiError(503, "Payment gateway timed out", { traceId: "trace-71" }),
    );
    const user = renderApp();

    await screen.findByRole("button", { name: "Pay $12.78" });
    await user.type(screen.getByLabelText("Cardholder name"), "Demo User");
    await user.type(screen.getByLabelText("Card number"), "4242424242424242");
    await user.type(screen.getByLabelText("Expiry"), "1230");
    await user.type(screen.getByLabelText("CVV"), "123");
    await user.click(screen.getByRole("button", { name: "Pay $12.78" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Trace trace-71.");

    await openWorkspace(user, "Admin console");
    expect(await screen.findByRole("region", { name: "Admin console" })).toBeInTheDocument();
    await openWorkspace(user, "Order food");

    // The retry after coming back is the same attempt: same form, same key.
    expect(screen.getByLabelText("Cardholder name")).toHaveValue("Demo User");
    expect(screen.getByRole("alert")).toHaveTextContent("Trace trace-71.");
    await user.click(await screen.findByRole("button", { name: "Pay $12.78" }));
    expect(await screen.findByText("Order #100 · PAID")).toBeInTheDocument();

    const keys = idempotencyKeys(backend.callsTo("POST", "/payments/checkout"));
    expect(keys).toHaveLength(2);
    expect(keys[0]).toEqual(expect.any(String));
    expect(keys[1]).toBe(keys[0]);
  });

  it("keeps a failed cancel's Idempotency-Key across a workspace switch", async () => {
    const backend = installFakeBackend({ user: demoUser });
    backend.state.orders = [orderFixture({ id: 7, status: "PAID" })];
    backend.on("POST", "/orders/7/cancel", () => apiError(500, "Database unavailable"));
    const user = renderApp();

    await screen.findByText("Demo is signed in");
    await user.click(screen.getByRole("button", { name: "Orders" }));
    await user.click(await screen.findByRole("button", { name: "Cancel order" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("500: Database unavailable");

    await openWorkspace(user, "Admin console");
    await openWorkspace(user, "Order food");
    await user.click(await screen.findByRole("button", { name: "Cancel order" }));
    expect(await screen.findByText("CANCELLED")).toBeInTheDocument();

    const keys = idempotencyKeys(backend.callsTo("POST", "/orders/7/cancel"));
    expect(keys).toHaveLength(2);
    expect(keys[1]).toBe(keys[0]);
  });

  it("keeps a failed status change's Idempotency-Key across a workspace switch", async () => {
    const backend = installFakeBackend({ user: demoUser });
    backend.state.orders = [orderFixture({ id: 7, status: "PAID" })];
    backend.on("PATCH", "/orders/7/status", () => apiError(503, "Order service unavailable"));
    const user = renderApp();

    await screen.findByText("Demo is signed in");
    await openWorkspace(user, "Admin console");
    await user.click(screen.getByRole("button", { name: "Order tools" }));
    await user.type(screen.getByLabelText("Order ID"), "7");
    await user.click(screen.getByRole("button", { name: "Update status" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("503: Order service unavailable");

    await openWorkspace(user, "Order food");
    await openWorkspace(user, "Admin console");
    expect(screen.getByLabelText("Order ID")).toHaveValue("7");
    await user.click(screen.getByRole("button", { name: "Update status" }));
    expect(await screen.findByText("Order #7 is ACCEPTED")).toBeInTheDocument();

    const keys = idempotencyKeys(backend.callsTo("PATCH", "/orders/7/status"));
    expect(keys).toHaveLength(2);
    expect(keys[1]).toBe(keys[0]);
  });

  it("pauses notification polling while another workspace is shown", async () => {
    vi.useFakeTimers({ toFake: ["setInterval", "clearInterval"] });
    const backend = installFakeBackend({ user: demoUser });
    const user = renderApp();

    await screen.findByText("Demo is signed in");
    await waitFor(() => expect(backend.callsTo("GET", "/notifications")).toHaveLength(1));
    await openWorkspace(user, "Admin console");
    act(() => {
      vi.advanceTimersByTime(NOTIFICATION_POLL_MS * 3);
    });
    await flushPromises();
    expect(backend.callsTo("GET", "/notifications")).toHaveLength(1);

    // Coming back reads them once, and polling resumes.
    await openWorkspace(user, "Order food");
    await waitFor(() => expect(backend.callsTo("GET", "/notifications")).toHaveLength(2));
    act(() => {
      vi.advanceTimersByTime(NOTIFICATION_POLL_MS);
    });
    await waitFor(() => expect(backend.callsTo("GET", "/notifications")).toHaveLength(3));
  });

  it("starts the same user on the default workspace after logging out and in again", async () => {
    installFakeBackend({ user: demoUser });
    const user = renderApp();

    await screen.findByText("Demo is signed in");
    await openWorkspace(user, "Admin console");
    expect(pressedWorkspace()).toEqual(["Admin console"]);

    await user.click(screen.getByRole("button", { name: "Logout" }));
    expect(await screen.findByText("Signed out.")).toBeInTheDocument();
    await signIn(user, demoUser.email, "demo123");

    expect(await screen.findByText("Demo is signed in")).toBeInTheDocument();
    expect(pressedWorkspace()).toEqual(["Order food"]);
    expect(screen.queryByRole("region", { name: "Admin console" })).toBeNull();
  });

  it("starts on the default workspace after the session expired and the user signs in again", async () => {
    const backend = installFakeBackend({ user: demoUser });
    const user = renderApp();

    await screen.findByText("Demo is signed in");
    await openWorkspace(user, "Admin console");
    backend.state.user = null;
    await user.click(screen.getByRole("button", { name: "Riders" }));
    expect(await screen.findByText(SESSION_EXPIRED_MESSAGE)).toBeInTheDocument();

    await signIn(user, demoUser.email, "demo123");
    expect(await screen.findByText("Demo is signed in")).toBeInTheDocument();
    expect(pressedWorkspace()).toEqual(["Order food"]);
  });
});
