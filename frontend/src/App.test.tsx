import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import type { UserEvent } from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { CartItem } from "./api/dto";
import App from "./App";
import { NOTIFICATION_POLL_MS } from "./hooks/useNotifications";
import { SESSION_EXPIRED_MESSAGE } from "./hooks/useSession";
import {
  apiError,
  deferred,
  demoUser,
  idempotencyKeys,
  installFakeBackend,
  json,
  menusFixture,
  orderFixture,
  requestJson,
} from "./test/fakeBackend";
import { flushPromises, formOf, renderApp, resetVisibility, setVisibility } from "./test/helpers";

const whopperLine: CartItem = {
  order_item_id: 501,
  menu_item_id: 10,
  restaurant_id: 1,
  menu_item_name: "Whopper",
  menu_item_description: "Flame grilled burger",
  menu_item_image_url: "",
  price: 6.39,
  quantity: 2,
};

const signedInLabel = () => screen.findByText(`${demoUser.first_name} is signed in`);

const fillPaymentForm = async (user: UserEvent, { cvv = "123" } = {}) => {
  await user.type(screen.getByLabelText("Cardholder name"), "Demo User");
  await user.type(screen.getByLabelText("Card number"), "4242424242424242");
  await user.type(screen.getByLabelText("Expiry"), "1230");
  await user.type(screen.getByLabelText("CVV"), cvv);
};

describe("guest browsing and auth", () => {
  it("lets guests browse restaurants and menu items before signing in", async () => {
    const backend = installFakeBackend();
    const user = renderApp();

    expect(await screen.findByRole("heading", { name: "Burger Barn" })).toBeInTheDocument();
    expect(await screen.findByText("Whopper")).toBeInTheDocument();
    expect(screen.getByText("Guest browsing")).toBeInTheDocument();
    expect(backend.callsTo("GET", "/restaurants/menu")).toHaveLength(1);
    expect(backend.callsTo("GET", "/cart")).toHaveLength(0);
    // A guest's 401 from /me is not an expired session.
    expect(screen.queryByText(SESSION_EXPIRED_MESSAGE)).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: /sign in to add/i }));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Sign in to add menu items to your cart.",
    );
    expect(backend.callsTo("POST", "/cart")).toHaveLength(0);
  });

  it("signs in, loads the session data, and signs out again", async () => {
    const backend = installFakeBackend();
    const user = renderApp();

    await user.type(await screen.findByLabelText("Email"), demoUser.email);
    await user.type(screen.getByLabelText("Password"), "demo123");
    await user.click(screen.getByRole("button", { name: "Sign in" }));

    expect(await signedInLabel()).toBeInTheDocument();
    expect(
      screen.getByText("Signed in. Cart, orders, and notifications are now active."),
    ).toBeInTheDocument();
    await waitFor(() => {
      expect(backend.callsTo("GET", "/cart")).toHaveLength(1);
      expect(backend.callsTo("GET", "/orders")).toHaveLength(1);
      expect(backend.callsTo("GET", "/notifications")).toHaveLength(1);
    });
    expect(backend.callsTo("POST", "/login")[0]?.body).toBe(
      "username=demo%40laifood.com&password=demo123",
    );

    const [logoutButton] = screen.getAllByRole("button", { name: "Logout" });
    if (!logoutButton) {
      throw new Error("No logout button");
    }
    await user.click(logoutButton);
    expect(await screen.findByText("Signed out.")).toBeInTheDocument();
    expect(screen.getByText("Guest browsing")).toBeInTheDocument();
    expect(screen.getByText("Sign in to load and edit your cart.")).toBeInTheDocument();
  });

  it("shows the backend message when the credentials are rejected", async () => {
    installFakeBackend();
    const user = renderApp();

    await user.type(await screen.findByLabelText("Email"), demoUser.email);
    await user.type(screen.getByLabelText("Password"), "wrong-password");
    await user.click(screen.getByRole("button", { name: "Sign in" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("401: Invalid email or password");
    expect(screen.getByText("Guest browsing")).toBeInTheDocument();
  });

  it("resets the signup form and switches to login after a successful signup", async () => {
    const backend = installFakeBackend();
    renderApp();

    fireEvent.click(await screen.findByRole("button", { name: "Signup" }));
    fireEvent.change(screen.getByLabelText("Email"), { target: { value: "new@example.com" } });
    fireEvent.change(screen.getByLabelText("Password"), { target: { value: "secret123" } });
    fireEvent.change(screen.getByLabelText("First name"), { target: { value: "New" } });
    fireEvent.change(screen.getByLabelText("Last name"), { target: { value: "User" } });
    fireEvent.submit(formOf(screen.getByRole("button", { name: "Create account" })));

    expect(
      await screen.findByText("Account created. Sign in with the new credentials."),
    ).toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Sign in" })).toBeInTheDocument();
    expect(backend.callsTo("POST", "/signup")[0]?.body).toBe(
      JSON.stringify({
        email: "new@example.com",
        password: "secret123",
        first_name: "New",
        last_name: "User",
      }),
    );
  });
});

describe("cart and checkout", () => {
  it("adds a menu item to the cart and shows the new quantity", async () => {
    const backend = installFakeBackend({ user: demoUser });
    const user = renderApp();

    await signedInLabel();
    await user.click(await screen.findByRole("button", { name: "Add to cart" }));

    const stepper = await screen.findByLabelText("Whopper quantity");
    expect(within(stepper).getByText("1")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Cart (1)" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Pay $6.39" })).toBeEnabled();
    expect(requestJson(backend.callsTo("POST", "/cart")[0])).toEqual({ menu_id: 10 });

    await user.click(within(stepper).getByRole("button", { name: "Increase Whopper" }));
    await waitFor(() => expect(within(stepper).getByText("2")).toBeInTheDocument());
    expect(screen.getByRole("button", { name: "Pay $12.78" })).toBeInTheDocument();
  });

  it("checks out with payment, confirms the order, and refreshes orders and notifications", async () => {
    const backend = installFakeBackend({ user: demoUser, cartItems: [whopperLine] });
    const user = renderApp();

    const payButton = await screen.findByRole("button", { name: "Pay $12.78" });
    await waitFor(() => expect(backend.callsTo("GET", "/orders")).toHaveLength(1));
    await fillPaymentForm(user);
    expect(screen.getByLabelText("Card number")).toHaveValue("4242 4242 4242 4242");
    await user.click(payButton);

    expect(await screen.findByText("Order #100 · PAID")).toBeInTheDocument();
    expect(screen.getByText("Your cart is empty. Checkout is disabled.")).toBeInTheDocument();
    expect(screen.getByLabelText("Card number")).toHaveValue("");

    const checkoutCalls = backend.callsTo("POST", "/payments/checkout");
    expect(idempotencyKeys(checkoutCalls)).toEqual([expect.any(String)]);
    expect(requestJson(checkoutCalls[0])).toEqual({
      cardholder_name: "Demo User",
      card_number: "4242424242424242",
      expiry: "12/30",
      cvv: "123",
    });
    await waitFor(() => {
      expect(backend.callsTo("GET", "/orders")).toHaveLength(2);
      expect(backend.callsTo("GET", "/notifications")).toHaveLength(2);
    });
    expect(await screen.findByRole("button", { name: "Notifications (1)" })).toBeInTheDocument();
  });

  it("shows a checkout error and retries the same payment with the same Idempotency-Key", async () => {
    const backend = installFakeBackend({ user: demoUser, cartItems: [whopperLine] });
    backend.on("POST", "/payments/checkout", () =>
      apiError(503, "Payment gateway timed out", { traceId: "trace-42" }),
    );
    backend.on("POST", "/payments/checkout", () =>
      apiError(503, "Payment gateway timed out", { traceId: "trace-43" }),
    );
    const user = renderApp();

    await screen.findByRole("button", { name: "Pay $12.78" });
    await fillPaymentForm(user);

    await user.click(screen.getByRole("button", { name: "Pay $12.78" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "503: Payment gateway timed out Trace trace-42.",
    );
    expect(screen.getByRole("button", { name: "Remove Whopper" })).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Pay $12.78" }));
    expect(await screen.findByText(/Trace trace-43/)).toBeInTheDocument();

    // Editing the payment details is a new attempt and must not reuse the old key.
    await user.clear(screen.getByLabelText("CVV"));
    await user.type(screen.getByLabelText("CVV"), "456");
    await user.click(screen.getByRole("button", { name: "Pay $12.78" }));
    expect(await screen.findByText("Order #100 · PAID")).toBeInTheDocument();

    const keys = idempotencyKeys(backend.callsTo("POST", "/payments/checkout"));
    expect(keys).toHaveLength(3);
    expect(keys[0]).toEqual(expect.any(String));
    expect(keys[1]).toBe(keys[0]);
    expect(keys[2]).not.toBe(keys[0]);
  });

  it("reuses the cancel Idempotency-Key when a cancel is retried", async () => {
    const backend = installFakeBackend({ user: demoUser });
    backend.state.orders = [orderFixture({ id: 7, status: "PAID" })];
    backend.on("POST", "/orders/7/cancel", () => apiError(500, "Database unavailable"));
    const user = renderApp();

    await signedInLabel();
    await user.click(screen.getByRole("button", { name: "Orders" }));
    await user.click(await screen.findByRole("button", { name: "Cancel order" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("500: Database unavailable");

    await user.click(screen.getByRole("button", { name: "Cancel order" }));
    expect(await screen.findByText("CANCELLED")).toBeInTheDocument();

    const keys = idempotencyKeys(backend.callsTo("POST", "/orders/7/cancel"));
    expect(keys).toHaveLength(2);
    expect(keys[0]).toEqual(expect.any(String));
    expect(keys[1]).toBe(keys[0]);
  });
});

describe("admin console order tools", () => {
  it("reuses the status-change Idempotency-Key on retry and starts a new one for a new change", async () => {
    const backend = installFakeBackend({ user: demoUser });
    backend.state.orders = [orderFixture({ id: 7, status: "PAID" })];
    backend.on("PATCH", "/orders/7/status", () =>
      apiError(503, "Order service unavailable", { traceId: "trace-51" }),
    );
    backend.on("PATCH", "/orders/7/status", () =>
      apiError(503, "Order service unavailable", { traceId: "trace-52" }),
    );
    const user = renderApp();

    await signedInLabel();
    await user.click(screen.getByRole("button", { name: "Admin console" }));
    await user.type(screen.getByLabelText("Order ID"), "7");
    expect(screen.getByLabelText("Target status")).toHaveValue("ACCEPTED");

    await user.click(screen.getByRole("button", { name: "Update status" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "503: Order service unavailable Trace trace-51.",
    );

    // Submitting the same order and status again is a retry of the same transition.
    await user.click(screen.getByRole("button", { name: "Update status" }));
    expect(await screen.findByText(/Trace trace-52/)).toBeInTheDocument();

    // A different target status is a new transition, not a retry.
    await user.selectOptions(screen.getByLabelText("Target status"), "PREPARING");
    await user.click(screen.getByRole("button", { name: "Update status" }));
    expect(await screen.findByText("Order #7 is PREPARING")).toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();

    const calls = backend.callsTo("PATCH", "/orders/7/status");
    expect(calls.map(requestJson)).toEqual([
      { status: "ACCEPTED" },
      { status: "ACCEPTED" },
      { status: "PREPARING" },
    ]);
    const keys = idempotencyKeys(calls);
    expect(keys[0]).toEqual(expect.any(String));
    expect(keys[1]).toBe(keys[0]);
    expect(keys[2]).toEqual(expect.any(String));
    expect(keys[2]).not.toBe(keys[0]);
  });
});

describe("session expiry", () => {
  it("returns to the logged-out state when a protected request comes back 401", async () => {
    const backend = installFakeBackend({ user: demoUser, cartItems: [whopperLine] });
    const user = renderApp();

    await signedInLabel();
    await screen.findByRole("button", { name: "Pay $12.78" });

    // The server-side session disappears (timeout, restart, logout elsewhere).
    backend.state.user = null;
    const menuStepper = screen.getByLabelText("Whopper quantity");
    await user.click(within(menuStepper).getByRole("button", { name: "Increase Whopper" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(SESSION_EXPIRED_MESSAGE);
    expect(screen.getByText("Session expired")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Logout" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Sign in" })).toBeInTheDocument();
    expect(screen.getByText("Sign in to load and edit your cart.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Cart (0)" })).toBeInTheDocument();

    // Signing in again restores the cart from the server.
    await user.type(screen.getByLabelText("Email"), demoUser.email);
    await user.type(screen.getByLabelText("Password"), "demo123");
    await user.click(screen.getByRole("button", { name: "Sign in" }));
    expect(await screen.findByRole("button", { name: "Pay $12.78" })).toBeInTheDocument();
    expect(screen.queryByText(SESSION_EXPIRED_MESSAGE)).not.toBeInTheDocument();
  });
});

describe("menu loading", () => {
  it("never lets a stale menu response overwrite the restaurant selected after it", async () => {
    const backend = installFakeBackend();
    const noodleMenu = deferred();
    const tacoMenu = deferred();
    backend.on("GET", "/restaurant/2/menu", () => noodleMenu.promise);
    backend.on("GET", "/restaurant/3/menu", () => tacoMenu.promise);
    const user = renderApp();

    expect(await screen.findByText("Whopper")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: /Noodle Nook/ }));
    expect(screen.getByText("Loading menu...")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: /Taco Town/ }));

    await act(async () => {
      tacoMenu.resolve(json(menusFixture[3]));
      await tacoMenu.promise;
    });
    expect(await screen.findByText("Fish Taco")).toBeInTheDocument();

    // The slower response for the restaurant the user already left arrives last.
    await act(async () => {
      noodleMenu.resolve(json(menusFixture[2]));
      await noodleMenu.promise;
    });
    await flushPromises();

    expect(screen.getByText("Fish Taco")).toBeInTheDocument();
    expect(screen.queryByText("Dan Dan Noodles")).not.toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Taco Town" })).toBeInTheDocument();
    expect(backend.callsTo("GET", "/restaurant/2/menu")[0]?.signal?.aborted).toBe(true);
    expect(backend.callsTo("GET", "/restaurant/3/menu")[0]?.signal?.aborted).toBe(false);
  });

  it("shows a clear error when the backend answers with an unexpected shape", async () => {
    const backend = installFakeBackend();
    backend.on("GET", "/restaurant/1/menu", () => json([{ id: 10, name: "Whopper" }]));
    renderApp();

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Unexpected response from the server: menu[0].restaurant_id should be a number, got undefined.",
    );
  });
});

describe("notification polling", () => {
  afterEach(() => {
    resetVisibility();
    vi.useRealTimers();
  });

  it("polls every 15 seconds, pauses while the tab is hidden, and catches up when it is shown", async () => {
    // Fake only the interval so Testing Library's own timeouts keep working.
    vi.useFakeTimers({ toFake: ["setInterval", "clearInterval"] });
    const backend = installFakeBackend({ user: demoUser });
    const notificationCalls = () => backend.callsTo("GET", "/notifications").length;
    render(<App />);

    await signedInLabel();
    await waitFor(() => expect(notificationCalls()).toBe(1));

    act(() => {
      vi.advanceTimersByTime(NOTIFICATION_POLL_MS);
    });
    await waitFor(() => expect(notificationCalls()).toBe(2));

    setVisibility("hidden");
    act(() => {
      vi.advanceTimersByTime(NOTIFICATION_POLL_MS * 4);
    });
    await flushPromises();
    expect(notificationCalls()).toBe(2);

    setVisibility("visible");
    await waitFor(() => expect(notificationCalls()).toBe(3));

    act(() => {
      vi.advanceTimersByTime(NOTIFICATION_POLL_MS);
    });
    await waitFor(() => expect(notificationCalls()).toBe(4));
  });

  it("stops polling when the user turns it off", async () => {
    vi.useFakeTimers({ toFake: ["setInterval", "clearInterval"] });
    const backend = installFakeBackend({ user: demoUser });
    const notificationCalls = () => backend.callsTo("GET", "/notifications").length;
    render(<App />);

    await signedInLabel();
    fireEvent.click(screen.getByRole("button", { name: "Notifications (0)" }));
    fireEvent.click(screen.getByLabelText("Poll every 15 seconds"));
    await waitFor(() => expect(notificationCalls()).toBe(1));

    act(() => {
      vi.advanceTimersByTime(NOTIFICATION_POLL_MS * 3);
    });
    await flushPromises();
    expect(notificationCalls()).toBe(1);
  });
});
