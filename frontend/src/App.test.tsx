import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import type { UserEvent } from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { CartItem, OrderItem } from "./api/dto";
import App from "./App";
import { AFTER_SALES_POLL_MS } from "./hooks/useAfterSales";
import { NOTIFICATION_POLL_MS } from "./hooks/useNotifications";
import { SESSION_EXPIRED_MESSAGE } from "./hooks/useSession";
import {
  afterSalesCaseFixture,
  apiError,
  deferred,
  demoUser,
  idempotencyKeys,
  installFakeBackend,
  json,
  menusFixture,
  orderFixture,
  orderNoFor,
  refundFixture,
  requestJson,
  settleAfterSalesRefund,
  USD,
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
  currency: USD,
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
    expect(backend.callsTo("GET", "/after-sales/cases")).toHaveLength(0);
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

  it("prices a restaurant's menu, cart and order in the restaurant's currency", async () => {
    installFakeBackend({ user: demoUser });
    const user = renderApp();

    await signedInLabel();
    await user.click(await screen.findByRole("button", { name: /Taco Town/ }));
    expect(await screen.findByText("Fish Taco")).toBeInTheDocument();
    expect(screen.getByText("€0.10")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Add to cart" }));
    const stepper = await screen.findByLabelText("Fish Taco quantity");
    for (const quantity of ["2", "3"]) {
      await user.click(within(stepper).getByRole("button", { name: "Increase Fish Taco" }));
      await waitFor(() => expect(within(stepper).getByText(quantity)).toBeInTheDocument());
    }
    // 3 x 0.10, summed in cents: not 0.30000000000000004.
    const payButton = screen.getByRole("button", { name: "Pay €0.30" });
    expect(screen.getByText("Subtotal €0.30")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "€0.30 total" })).toBeInTheDocument();

    await fillPaymentForm(user);
    await user.click(payButton);
    const confirmation = await screen.findByText("Order confirmation");
    expect(confirmation.closest("article")).toHaveTextContent("3 x Fish Taco · €0.30");
  });

  it("checks out with payment, confirms the order, and refreshes orders and notifications", async () => {
    const backend = installFakeBackend({ user: demoUser, cartItems: [whopperLine] });
    const user = renderApp();

    const payButton = await screen.findByRole("button", { name: "Pay $12.78" });
    await waitFor(() => expect(backend.callsTo("GET", "/orders")).toHaveLength(1));
    await fillPaymentForm(user);
    expect(screen.getByLabelText("Card number")).toHaveValue("4242 4242 4242 4242");
    await user.click(payButton);

    expect(await screen.findByText(`Order #${orderNoFor(100)} · PAID`)).toBeInTheDocument();
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
    // The checkout reads the orders again; the order's notification, newer than any
    // before it, reads them once more (and the after-sales overview).
    await waitFor(() => {
      expect(backend.callsTo("GET", "/orders")).toHaveLength(3);
      expect(backend.callsTo("GET", "/notifications")).toHaveLength(2);
      expect(backend.callsTo("GET", "/after-sales/cases")).toHaveLength(2);
    });
    expect(await screen.findByRole("button", { name: "Notifications (1)" })).toBeInTheDocument();
  });

  it("names the order by its public order number in the confirmation, orders, and notifications", async () => {
    const backend = installFakeBackend({ user: demoUser, cartItems: [whopperLine] });
    const user = renderApp();

    await screen.findByRole("button", { name: "Pay $12.78" });
    await fillPaymentForm(user);
    await user.click(screen.getByRole("button", { name: "Pay $12.78" }));
    const orderNo = orderNoFor(100);
    expect(await screen.findByText(`Order #${orderNo} · PAID`)).toBeInTheDocument();

    await user.click(await screen.findByRole("button", { name: "Notifications (1)" }));
    const panel = await screen.findByRole("region", { name: "Order notifications" });
    const card = await within(panel).findByRole("article");
    expect(card).toHaveTextContent(`Order #${orderNo} was paid successfully.`);
    expect(card).toHaveTextContent(`Order #${orderNo} ·`);

    await user.click(screen.getByRole("button", { name: "Orders" }));
    expect(await screen.findByText(`Order #${orderNo}`)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Status history" }));
    expect(
      await screen.findByRole("region", { name: `Status history of Order #${orderNo}` }),
    ).toBeInTheDocument();
    // The internal id stays in the API paths and never reaches the customer.
    expect(screen.queryByText(/Order #100\b/)).toBeNull();
    expect(backend.callsTo("GET", "/orders/100/history")).toHaveLength(1);
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
    expect(await screen.findByText(`Order #${orderNoFor(100)} · PAID`)).toBeInTheDocument();

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

describe("after-sales", () => {
  const whopperItem: OrderItem = {
    id: 5,
    menu_item_id: 10,
    restaurant_id: 1,
    price: 6.39,
    currency: USD,
    quantity: 2,
    menu_item_name: "Whopper",
    menu_item_description: null,
    menu_item_image_url: null,
  };
  const completedOrder = orderFixture({
    id: 7,
    status: "COMPLETED",
    total_price: 12.78,
    items: [whopperItem],
  });
  const refundRequest = () =>
    screen.findByRole("region", { name: `Refund request for Order #${orderNoFor(7)}` });

  const openOrders = async (user: UserEvent) => {
    await signedInLabel();
    await user.click(screen.getByRole("button", { name: "Orders" }));
  };

  /** Opens the order's request form and fills it for one cold Whopper. */
  const fillRequest = async (user: UserEvent, button = "Request a refund") => {
    await user.click(await screen.findByRole("button", { name: button }));
    await user.click(screen.getByLabelText("Selected items"));
    await user.click(screen.getByRole("button", { name: "More Whopper" }));
    await user.selectOptions(screen.getByLabelText("Reason"), "QUALITY_ISSUE");
    await user.type(screen.getByLabelText("What went wrong?"), "Cold");
  };

  afterEach(() => {
    vi.useRealTimers();
  });

  it("retries a failed request with its Idempotency-Key and a changed one with a new key", async () => {
    const backend = installFakeBackend({ user: demoUser });
    backend.state.orders = [completedOrder];
    backend.on("POST", "/orders/7/after-sales", () =>
      apiError(503, "After-sales unavailable", { traceId: "trace-81" }),
    );
    backend.on("POST", "/orders/7/after-sales", () =>
      apiError(503, "After-sales unavailable", { traceId: "trace-82" }),
    );
    const user = renderApp();

    await openOrders(user);
    await fillRequest(user);
    expect(
      screen.getByRole("form", { name: `Refund request for Order #${orderNoFor(7)}` }),
    ).toHaveTextContent("Refund amount $6.39");
    await user.click(screen.getByRole("button", { name: "Submit request" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "503: After-sales unavailable Trace trace-81.",
    );

    // The same request again is a retry of the same attempt.
    await user.click(screen.getByRole("button", { name: "Submit request" }));
    expect(await screen.findByText(/Trace trace-82/)).toBeInTheDocument();

    // Asking for both Whoppers is another request.
    await user.click(screen.getByRole("button", { name: "More Whopper" }));
    await user.click(screen.getByRole("button", { name: "Submit request" }));
    const request = await refundRequest();
    expect(within(request).getByText("Waiting for the restaurant")).toBeInTheDocument();
    expect(within(request).getByText(/^Restaurant answers in/)).toBeInTheDocument();
    expect(within(request).getByText("2 x Whopper · $12.78")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Request a refund" })).toBeNull();

    const calls = backend.callsTo("POST", "/orders/7/after-sales");
    expect(calls.map((call) => requestJson(call))).toEqual([
      {
        scope: "ITEMS",
        lines: [{ order_item_id: 5, quantity: 1 }],
        reason_code: "QUALITY_ISSUE",
        description: "Cold",
      },
      {
        scope: "ITEMS",
        lines: [{ order_item_id: 5, quantity: 1 }],
        reason_code: "QUALITY_ISSUE",
        description: "Cold",
      },
      {
        scope: "ITEMS",
        lines: [{ order_item_id: 5, quantity: 2 }],
        reason_code: "QUALITY_ISSUE",
        description: "Cold",
      },
    ]);
    const keys = idempotencyKeys(calls);
    expect(keys[0]).toEqual(expect.any(String));
    expect(keys[1]).toBe(keys[0]);
    expect(keys[2]).toEqual(expect.any(String));
    expect(keys[2]).not.toBe(keys[0]);
  });

  it("retries a failed withdrawal with its key, then requests once more with a new key", async () => {
    const backend = installFakeBackend({ user: demoUser });
    backend.state.orders = [completedOrder];
    backend.on("POST", /^\/after-sales\/cases\/\d+\/withdraw$/, () =>
      apiError(500, "Database unavailable"),
    );
    const user = renderApp();

    await openOrders(user);
    await fillRequest(user);
    await user.click(screen.getByRole("button", { name: "Submit request" }));
    const request = await refundRequest();

    await user.click(within(request).getByRole("button", { name: "Withdraw request" }));
    const dialog = screen.getByRole("dialog", { name: "Withdraw this refund request?" });
    await user.click(within(dialog).getByRole("button", { name: "Withdraw" }));
    expect(await within(dialog).findByRole("alert")).toHaveTextContent("500: Database unavailable");
    await user.click(within(dialog).getByRole("button", { name: "Withdraw" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    expect(within(request).getByText("Closed: Withdrawn")).toBeInTheDocument();

    // The same draft again is the order's second (and last) request, not a retry.
    await fillRequest(user, "Request a refund again (1 left)");
    await user.click(screen.getByRole("button", { name: "Submit request" }));
    expect(
      await screen.findByRole("heading", { name: "Second refund request" }),
    ).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Request a refund/ })).toBeNull();

    const withdrawKeys = idempotencyKeys(
      backend.calls.filter((call) => call.method === "POST" && call.path.endsWith("/withdraw")),
    );
    expect(withdrawKeys).toHaveLength(2);
    expect(withdrawKeys[1]).toBe(withdrawKeys[0]);
    const openCalls = backend.callsTo("POST", "/orders/7/after-sales");
    expect(requestJson(openCalls[1])).toEqual(requestJson(openCalls[0]));
    const openKeys = idempotencyKeys(openCalls);
    expect(openKeys[1]).toEqual(expect.any(String));
    expect(openKeys[1]).not.toBe(openKeys[0]);
    expect(backend.state.afterSalesCases.map((item) => item.attempt_no)).toEqual([2, 1]);
  });

  it("drops an overview response that a newer read superseded", async () => {
    const backend = installFakeBackend({ user: demoUser });
    backend.state.orders = [completedOrder];
    const offered = afterSalesCaseFixture({
      id: 900,
      order_id: 7,
      status: "COUNTER_OFFERED",
      offered_amount: 3,
      merchant_decision: "OFFERED",
    });
    backend.state.afterSalesCases = [offered];
    const slowFirstRead = deferred();
    backend.on("GET", "/after-sales/cases", () => slowFirstRead.promise);
    const user = renderApp();

    await openOrders(user);
    await user.click(screen.getByRole("button", { name: "Refresh" }));
    const request = await refundRequest();
    expect(within(request).getByText("Partial refund offered")).toBeInTheDocument();

    // The first read, from before the offer, answers last.
    await act(async () => {
      slowFirstRead.resolve(
        json({ cases: [{ ...offered, status: "PENDING_MERCHANT", offered_amount: null }] }),
      );
      await slowFirstRead.promise;
    });
    await flushPromises();
    expect(within(request).getByText("Partial refund offered")).toBeInTheDocument();
    expect(within(request).getByRole("button", { name: "Accept $3.00" })).toBeInTheDocument();
    expect(backend.callsTo("GET", "/after-sales/cases")[0]?.signal?.aborted).toBe(true);
  });

  it("polls a refund in progress and stops once it is refunded", async () => {
    vi.useFakeTimers({ toFake: ["setInterval", "clearInterval"] });
    const backend = installFakeBackend({ user: demoUser });
    backend.state.orders = [completedOrder];
    backend.state.afterSalesCases = [
      afterSalesCaseFixture({
        id: 900,
        order_id: 7,
        status: "REFUNDING",
        approved_amount: 6.39,
        merchant_decision: "APPROVED",
        resolution: "MERCHANT_APPROVED",
        deadline_at: null,
        refund: refundFixture({ id: 901, case_id: 900, order_id: 7 }),
      }),
    ];
    const overviewReads = () => backend.callsTo("GET", "/after-sales/cases").length;
    render(<App />);

    await signedInLabel();
    fireEvent.click(screen.getByRole("button", { name: "Orders" }));
    const request = await refundRequest();
    expect(within(request).getByText("Refund of $6.39: Pending")).toBeInTheDocument();
    expect(overviewReads()).toBe(1);

    act(() => {
      vi.advanceTimersByTime(AFTER_SALES_POLL_MS);
    });
    await waitFor(() => expect(overviewReads()).toBe(2));

    settleAfterSalesRefund(backend.state, 900);
    act(() => {
      vi.advanceTimersByTime(AFTER_SALES_POLL_MS);
    });
    await waitFor(() =>
      expect(within(request).getByText("Refund of $6.39: Refunded")).toBeInTheDocument(),
    );
    expect(
      within(request).getByText("Refunded", { selector: ".status-badge" }),
    ).toBeInTheDocument();
    expect(overviewReads()).toBe(3);

    // Everything is final: no more reads.
    act(() => {
      vi.advanceTimersByTime(AFTER_SALES_POLL_MS * 3);
    });
    await flushPromises();
    expect(overviewReads()).toBe(3);
  });

  it("stops polling a refund that failed, says support will call, and reads it again on Refresh", async () => {
    vi.useFakeTimers({ toFake: ["setInterval", "clearInterval"] });
    const backend = installFakeBackend({ user: demoUser });
    backend.state.orders = [completedOrder];
    const approved = afterSalesCaseFixture({
      id: 900,
      order_id: 7,
      status: "REFUNDING",
      approved_amount: 6.39,
      merchant_decision: "APPROVED",
      resolution: "MERCHANT_APPROVED",
      deadline_at: null,
      refund: refundFixture({ id: 901, case_id: 900, order_id: 7 }),
    });
    backend.state.afterSalesCases = [approved];
    const overviewReads = () => backend.callsTo("GET", "/after-sales/cases").length;
    render(<App />);

    await signedInLabel();
    fireEvent.click(screen.getByRole("button", { name: "Orders" }));
    const request = await refundRequest();
    expect(within(request).getByText("Refund of $6.39: Pending")).toBeInTheDocument();

    // The executor gives up: the case stays REFUNDING until an admin retries the refund.
    const { refund } = approved;
    if (!refund) {
      throw new Error("missing fixture");
    }
    Object.assign(refund, {
      status: "FAILED",
      attempts: 5,
      last_error: "Gateway timeout",
      next_attempt_at: null,
    });
    act(() => {
      vi.advanceTimersByTime(AFTER_SALES_POLL_MS);
    });
    await waitFor(() =>
      expect(within(request).getByText("Refund of $6.39: Failed")).toBeInTheDocument(),
    );
    expect(
      within(request).getByText("Refund failed", { selector: ".status-badge" }),
    ).toBeInTheDocument();
    expect(within(request).getByRole("status")).toHaveTextContent(
      "The refund did not go through. Our support team will contact you about it.",
    );
    // The reason is for the platform only.
    expect(within(request).queryByText(/Gateway timeout/)).toBeNull();
    expect(overviewReads()).toBe(2);

    // Nothing moves it on its own: no more reads.
    act(() => {
      vi.advanceTimersByTime(AFTER_SALES_POLL_MS * 3);
    });
    await flushPromises();
    expect(overviewReads()).toBe(2);

    // An admin retried the refund: the customer's Refresh shows it, and polling resumes.
    Object.assign(refund, { status: "PENDING", last_error: null });
    fireEvent.click(screen.getByRole("button", { name: "Refresh" }));
    await waitFor(() =>
      expect(within(request).getByText("Refund of $6.39: Pending")).toBeInTheDocument(),
    );
    expect(within(request).queryByRole("status")).toBeNull();
    expect(overviewReads()).toBe(3);
    act(() => {
      vi.advanceTimersByTime(AFTER_SALES_POLL_MS);
    });
    await waitFor(() => expect(overviewReads()).toBe(4));
  });

  it("offers a refund once a polled notification says the order completed", async () => {
    vi.useFakeTimers({ toFake: ["setInterval", "clearInterval"] });
    const backend = installFakeBackend({ user: demoUser });
    const notice = (id: number, eventType: string, title: string, message: string) => ({
      id,
      order_id: 7,
      order_no: orderNoFor(7),
      event_type: eventType,
      title,
      message: `Order #${orderNoFor(7)} ${message}`,
      created_at: "2026-10-16T12:00:00Z",
    });
    backend.state.orders = [{ ...completedOrder, status: "PREPARING" }];
    backend.state.notifications = [
      notice(40, "order.preparing", "Preparing your order", "is now being prepared."),
    ];
    const orderReads = () => backend.callsTo("GET", "/orders").length;
    const overviewReads = () => backend.callsTo("GET", "/after-sales/cases").length;
    const notificationReads = () => backend.callsTo("GET", "/notifications").length;
    render(<App />);

    await signedInLabel();
    fireEvent.click(screen.getByRole("button", { name: "Orders" }));
    expect(await screen.findByText("PREPARING", { selector: ".status-badge" })).toBeInTheDocument();
    await waitFor(() => expect(notificationReads()).toBe(1));
    await flushPromises();
    expect(screen.queryByRole("button", { name: "Request a refund" })).toBeNull();
    expect([orderReads(), overviewReads()]).toEqual([1, 1]);

    // What the backend does on completion: the order turns COMPLETED, then the
    // notification consumer writes the order.completed notice.
    backend.state.orders = [completedOrder];
    backend.state.notifications.unshift(
      notice(41, "order.completed", "Order completed", "is completed and ready."),
    );
    act(() => {
      vi.advanceTimersByTime(NOTIFICATION_POLL_MS);
    });

    expect(await screen.findByRole("button", { name: "Request a refund" })).toBeInTheDocument();
    expect(screen.getByText("COMPLETED", { selector: ".status-badge" })).toBeInTheDocument();
    expect([orderReads(), overviewReads()]).toEqual([2, 2]);

    // Polls that bring nothing newer read nothing else.
    for (const reads of [3, 4]) {
      act(() => {
        vi.advanceTimersByTime(NOTIFICATION_POLL_MS);
      });
      await waitFor(() => expect(notificationReads()).toBe(reads));
      await flushPromises();
    }
    expect([orderReads(), overviewReads()]).toEqual([2, 2]);
  });

  it("keeps a cancel's result over an order read a notification started before it", async () => {
    vi.useFakeTimers({ toFake: ["setInterval", "clearInterval"] });
    const backend = installFakeBackend({ user: demoUser });
    const notice = (id: number, eventType: string, title: string, message: string) => ({
      id,
      order_id: 7,
      order_no: orderNoFor(7),
      event_type: eventType,
      title,
      message: `Order #${orderNoFor(7)} ${message}`,
      created_at: "2026-10-16T12:00:00Z",
    });
    const paidOrder = orderFixture({ id: 7, status: "PAID" });
    // A copy: the fake's cancel changes the order in its state.
    backend.state.orders = [{ ...paidOrder }];
    backend.state.notifications = [
      notice(40, "order.paid", "Payment confirmed", "was paid successfully with 1 item(s)."),
    ];
    render(<App />);

    await signedInLabel();
    fireEvent.click(screen.getByRole("button", { name: "Orders" }));
    expect(await screen.findByText("PAID", { selector: ".status-badge" })).toBeInTheDocument();
    await waitFor(() => expect(backend.callsTo("GET", "/notifications")).toHaveLength(1));

    // A new notification starts an order read, which answers only after the cancel.
    const slowRead = deferred();
    backend.on("GET", "/orders", () => slowRead.promise);
    backend.state.notifications.unshift(
      notice(41, "order.accepted", "Order accepted", "was accepted by the restaurant."),
    );
    act(() => {
      vi.advanceTimersByTime(NOTIFICATION_POLL_MS);
    });
    await waitFor(() => expect(backend.callsTo("GET", "/orders")).toHaveLength(2));

    fireEvent.click(screen.getByRole("button", { name: "Cancel order" }));
    expect(await screen.findByText("CANCELLED", { selector: ".status-badge" })).toBeInTheDocument();

    // The read from before the cancel carries the PAID order: it must not come back.
    await act(async () => {
      slowRead.resolve(json([paidOrder]));
      await slowRead.promise;
    });
    await flushPromises();
    expect(screen.getByText("CANCELLED", { selector: ".status-badge" })).toBeInTheDocument();
    expect(screen.queryByText("PAID", { selector: ".status-badge" })).toBeNull();
  });

  it("shows the automatic refund of a paid order the customer cancelled", async () => {
    const backend = installFakeBackend({ user: demoUser });
    backend.state.orders = [orderFixture({ id: 7, status: "PAID", total_price: 9 })];
    const user = renderApp();

    await openOrders(user);
    await user.click(await screen.findByRole("button", { name: "Cancel order" }));

    expect(await screen.findByText("Automatic refund $9.00: Refunding")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Request a refund/ })).toBeNull();
    expect(backend.callsTo("GET", "/after-sales/cases").length).toBeGreaterThanOrEqual(2);
  });

  it("refunds only half of an order the customer cancelled after the rider picked it up", async () => {
    const backend = installFakeBackend({ user: demoUser });
    backend.state.orders = [orderFixture({ id: 7, status: "ACCEPTED", total_price: 9.01 })];
    backend.state.orderPickups.set(7, "2026-10-21T09:30:00");
    const user = renderApp();

    await openOrders(user);
    await user.click(await screen.findByRole("button", { name: "Cancel order" }));

    // 50% of $9.01, rounded half-even like the backend: $4.50.
    expect(await screen.findByText("Automatic refund $4.50: Refunding")).toBeInTheDocument();
  });

  it("shows that the automatic refund of a cancelled order failed", async () => {
    const backend = installFakeBackend({ user: demoUser });
    backend.state.orders = [orderFixture({ id: 7, status: "PAID", total_price: 9 })];
    const user = renderApp();

    await openOrders(user);
    await user.click(await screen.findByRole("button", { name: "Cancel order" }));
    expect(await screen.findByText("Automatic refund $9.00: Refunding")).toBeInTheDocument();
    expect(screen.queryByText(/Our support team will contact you/)).toBeNull();

    // The executor gives up on the refund the cancel opened.
    const refund = backend.state.afterSalesCases.find((item) => item.order_id === 7)?.refund;
    if (!refund) {
      throw new Error("the cancel opened no refund");
    }
    Object.assign(refund, { status: "FAILED", attempts: 5, last_error: "Card account closed" });
    await user.click(screen.getByRole("button", { name: "Refresh" }));

    expect(await screen.findByText("Automatic refund $9.00: Refund failed")).toBeInTheDocument();
    expect(
      screen.getByText(
        "The refund did not go through. Our support team will contact you about it.",
      ),
    ).toBeInTheDocument();
    expect(screen.queryByText(/Card account closed/)).toBeNull();
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
    await user.click(screen.getByRole("button", { name: "Order tools" }));
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
    expect(
      await screen.findByText(`Order #${orderNoFor(7)} (id 7) is PREPARING`),
    ).toBeInTheDocument();
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
