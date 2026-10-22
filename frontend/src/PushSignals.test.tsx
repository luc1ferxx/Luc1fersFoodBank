import { act, screen, waitFor, within } from "@testing-library/react";
import type { UserEvent } from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { type OrderNotification, ROLES } from "./api/dto";
import { MERCHANT_POLL_MS } from "./hooks/useMerchantOrders";
import { NOTIFICATION_POLL_MS } from "./hooks/useNotifications";
import { PUSH_FALLBACK_POLL_MS } from "./hooks/useServerSignals";
import { SESSION_EXPIRED_MESSAGE } from "./hooks/useSession";
import {
  afterSalesCaseFixture,
  apiError,
  deliveryFixture,
  demoUser,
  installFakeBackend,
  merchantOrderFixture,
  merchantUser,
  orderFixture,
  orderNoFor,
  refundFixture,
  riderUser,
  settleAfterSalesRefund,
} from "./test/fakeBackend";
import { eventStreams, serveEventStreams } from "./test/fakeEventSource";
import {
  connectEventStream,
  flushPromises,
  renderApp,
  resetVisibility,
  setVisibility,
} from "./test/helpers";

/* ADR 0003: change signals over GET /events/stream, with polling as the fallback. */

const MERCHANT_DEACTIVATED = "This merchant account is deactivated. Ask an admin to reactivate it.";

const orderCard = (orderId: number) =>
  screen.findByRole("article", { name: `Order #${orderNoFor(orderId)}` });

const deliveryCard = (deliveryId: number) =>
  screen.findByRole("article", { name: `Delivery #${deliveryId}` });

const withMerchantActive = (active: boolean) => ({
  ...merchantUser,
  merchant: { merchant_id: 3, restaurant_id: 1, restaurant_name: "Burger Barn", active },
});

const merchantBackend = () => {
  const backend = installFakeBackend({ user: merchantUser });
  backend.state.merchantOrders = [merchantOrderFixture({ order_id: 7, status: "PAID" })];
  return backend;
};

type Backend = ReturnType<typeof installFakeBackend>;

const reads = (backend: Backend, path: string) => backend.callsTo("GET", path).length;

const notice = (id: number, orderId: number, title: string): OrderNotification => ({
  id,
  order_id: orderId,
  order_no: orderNoFor(orderId),
  event_type: "order.completed",
  title,
  message: `Order #${orderNoFor(orderId)} is completed and ready.`,
  created_at: "2026-10-16T12:00:00",
});

const signIn = async (user: UserEvent, email: string, password: string) => {
  await user.type(await screen.findByLabelText("Email"), email);
  await user.type(screen.getByLabelText("Password"), password);
  await user.click(screen.getByRole("button", { name: "Sign in" }));
};

const openWorkspace = async (user: UserEvent, name: string) => {
  await user.click(
    within(screen.getByRole("navigation", { name: "Workspaces" })).getByRole("button", { name }),
  );
};

describe("push signals", () => {
  afterEach(() => {
    resetVisibility();
    vi.useRealTimers();
  });

  it("shows a merchant a newly paid order on its signal, without waiting for a poll", async () => {
    vi.useFakeTimers({ toFake: ["setInterval", "clearInterval"] });
    const backend = merchantBackend();
    renderApp();

    await orderCard(7);
    expect(reads(backend, "/merchant/orders")).toBe(1);
    const stream = await connectEventStream();
    expect(stream.url).toBe("/events/stream");
    expect(stream.recipients).toEqual(["customer:21", "restaurant:1"]);
    // Opening reads once: signals sent before the stream opened are lost.
    await waitFor(() => expect(reads(backend, "/merchant/orders")).toBe(2));

    // Another customer pays; the inbox consumer's commit signals the restaurant.
    act(() => backend.receiveMerchantOrder(merchantOrderFixture({ order_id: 9 })));

    expect(await orderCard(9)).toBeInTheDocument();
    expect(reads(backend, "/merchant/orders")).toBe(3);
    expect(
      within(screen.getByRole("group", { name: "Merchant sections" })).getByRole("button", {
        name: "Orders, 2 to accept",
      }),
    ).toBeInTheDocument();
  });

  it("shows a rider a new assignment on its signal, without waiting for a poll", async () => {
    vi.useFakeTimers({ toFake: ["setInterval", "clearInterval"] });
    const backend = installFakeBackend({ user: riderUser });
    backend.state.deliveries = [deliveryFixture({ delivery_id: 5, order_id: 101 })];
    renderApp();

    await deliveryCard(5);
    const stream = await connectEventStream();
    expect(stream.recipients).toEqual(["customer:31", "rider:7"]);
    await waitFor(() => expect(reads(backend, "/rider/deliveries")).toBe(2));

    // Dispatch assigns the rider another order.
    act(() => backend.assignDelivery(deliveryFixture({ delivery_id: 6, order_id: 102 })));

    expect(await deliveryCard(6)).toBeInTheDocument();
    expect(reads(backend, "/rider/deliveries")).toBe(3);
    // The profile is read with the deliveries: its active-delivery count moved too.
    expect(reads(backend, "/rider/me")).toBe(3);
  });

  it("shows a customer a new notification at once, and reads the orders and refund requests after it", async () => {
    vi.useFakeTimers({ toFake: ["setInterval", "clearInterval"] });
    const backend = installFakeBackend({ user: demoUser });
    backend.state.orders = [orderFixture({ id: 7, status: "PREPARING" })];
    const user = renderApp();

    await screen.findByText("Demo is signed in");
    await user.click(screen.getByRole("button", { name: "Notifications (0)" }));
    const stream = await connectEventStream();
    expect(stream.recipients).toEqual(["customer:1"]);
    await waitFor(() => expect(reads(backend, "/notifications")).toBe(2));
    await flushPromises();
    expect([reads(backend, "/orders"), reads(backend, "/after-sales/cases")]).toEqual([1, 1]);

    // The order completes and the notification consumer writes its notice.
    backend.state.orders = [orderFixture({ id: 7, status: "COMPLETED" })];
    act(() => backend.addNotification(notice(41, 7, "Order completed")));

    expect(await screen.findByRole("heading", { name: "Order completed" })).toBeInTheDocument();
    expect(reads(backend, "/notifications")).toBe(3);
    // A newer notification than any before: OrderingPage reads what it is about.
    await waitFor(() =>
      expect([reads(backend, "/orders"), reads(backend, "/after-sales/cases")]).toEqual([2, 2]),
    );
  });

  it("stretches the poll to 60 seconds while the stream is open", async () => {
    vi.useFakeTimers({ toFake: ["setInterval", "clearInterval"] });
    const backend = merchantBackend();
    renderApp();

    await orderCard(7);
    await connectEventStream();
    await waitFor(() => expect(reads(backend, "/merchant/orders")).toBe(2));

    act(() => {
      vi.advanceTimersByTime(MERCHANT_POLL_MS * 3);
    });
    await flushPromises();
    expect(reads(backend, "/merchant/orders")).toBe(2);

    // The fallback poll still catches a lost signal.
    act(() => {
      vi.advanceTimersByTime(PUSH_FALLBACK_POLL_MS - MERCHANT_POLL_MS * 3);
    });
    await waitFor(() => expect(reads(backend, "/merchant/orders")).toBe(3));
  });

  it("polls at the old interval again while the stream is down", async () => {
    vi.useFakeTimers({ toFake: ["setInterval", "clearInterval"] });
    const backend = installFakeBackend({ user: demoUser });
    renderApp();

    await screen.findByText("Demo is signed in");
    const stream = await connectEventStream();
    await waitFor(() => expect(reads(backend, "/notifications")).toBe(2));

    // The connection breaks; the browser reconnects in a few seconds meanwhile.
    act(() => stream.drop());
    act(() => {
      vi.advanceTimersByTime(NOTIFICATION_POLL_MS);
    });
    await waitFor(() => expect(reads(backend, "/notifications")).toBe(3));
    act(() => {
      vi.advanceTimersByTime(NOTIFICATION_POLL_MS);
    });
    await waitFor(() => expect(reads(backend, "/notifications")).toBe(4));
  });

  it("reads once on a reconnect, then waits for signals again", async () => {
    vi.useFakeTimers({ toFake: ["setInterval", "clearInterval"] });
    const backend = merchantBackend();
    renderApp();

    await orderCard(7);
    const stream = await connectEventStream();
    await waitFor(() => expect(reads(backend, "/merchant/orders")).toBe(2));
    act(() => stream.drop());
    // A paid order while the stream is down: its signal is lost.
    backend.state.merchantOrders.unshift(merchantOrderFixture({ order_id: 9 }));

    act(() => stream.connect());

    expect(await orderCard(9)).toBeInTheDocument();
    await flushPromises();
    expect(reads(backend, "/merchant/orders")).toBe(3);
    act(() => {
      vi.advanceTimersByTime(MERCHANT_POLL_MS * 3);
    });
    await flushPromises();
    expect(reads(backend, "/merchant/orders")).toBe(3);
    expect(eventStreams()).toHaveLength(1);
  });

  it("keeps polling at the old interval when the server refuses the stream", async () => {
    vi.useFakeTimers({ toFake: ["setInterval", "clearInterval"] });
    const backend = merchantBackend();
    renderApp();

    await orderCard(7);
    // Over the per-user stream limit: 429, which EventSource reports as error, CLOSED.
    serveEventStreams(() => ({ refusedWith: 429 }));
    const stream = await connectEventStream();
    expect(stream.readyState).toBe(stream.CLOSED);
    await flushPromises();
    expect(reads(backend, "/merchant/orders")).toBe(1);

    act(() => {
      vi.advanceTimersByTime(MERCHANT_POLL_MS);
    });
    await waitFor(() => expect(reads(backend, "/merchant/orders")).toBe(2));
  });

  it("closes the stream on forbidden and shows the console's deactivation notice", async () => {
    vi.useFakeTimers({ toFake: ["setInterval", "clearInterval"] });
    const backend = merchantBackend();
    renderApp();

    const paid = await orderCard(7);
    const stream = await connectEventStream();
    await waitFor(() => expect(reads(backend, "/merchant/orders")).toBe(2));

    // An admin deactivates the merchant; the next keepalive finds out.
    backend.state.user = withMerchantActive(false);
    act(() => backend.keepalive());

    expect(stream.closedByClient).toBe(true);
    expect(stream.readyState).toBe(stream.CLOSED);
    expect(await screen.findByText(MERCHANT_DEACTIVATED)).toBeInTheDocument();
    expect(within(paid).getByRole("button", { name: "Accept" })).toBeDisabled();
    expect(screen.queryByRole("alert")).toBeNull();
    expect(reads(backend, "/merchant/orders")).toBe(3);

    // No new stream and no polling of the 403; Refresh still checks again.
    act(() => {
      vi.advanceTimersByTime(PUSH_FALLBACK_POLL_MS * 2);
    });
    await flushPromises();
    expect(eventStreams()).toHaveLength(1);
    expect(reads(backend, "/merchant/orders")).toBe(3);
  });

  it("opens no stream for a guest", async () => {
    installFakeBackend();
    renderApp();

    expect(await screen.findByText("Guest browsing")).toBeInTheDocument();
    await flushPromises();
    expect(eventStreams()).toHaveLength(0);
  });

  it("closes the stream on logout and opens a new one for the next login", async () => {
    installFakeBackend({ user: demoUser });
    const user = renderApp();

    await screen.findByText("Demo is signed in");
    const first = await connectEventStream();

    const [logout] = screen.getAllByRole("button", { name: "Logout" });
    if (!logout) {
      throw new Error("No logout button");
    }
    await user.click(logout);
    expect(await screen.findByText("Signed out.")).toBeInTheDocument();
    expect(first.closedByClient).toBe(true);
    await flushPromises();
    expect(eventStreams()).toHaveLength(1);

    await signIn(user, demoUser.email, "demo123");
    expect(await screen.findByText("Demo is signed in")).toBeInTheDocument();
    const second = await connectEventStream();
    expect(second).not.toBe(first);
    expect(second.recipients).toEqual(["customer:1"]);
    expect(eventStreams()).toHaveLength(2);
  });

  it("closes the stream when the session expires", async () => {
    const backend = merchantBackend();
    renderApp();

    await orderCard(7);
    const stream = await connectEventStream();
    backend.on("GET", "/merchant/orders", () => apiError(401, "Authentication required"));
    act(() => backend.receiveMerchantOrder(merchantOrderFixture({ order_id: 9 })));

    expect(await screen.findByText(SESSION_EXPIRED_MESSAGE)).toBeInTheDocument();
    expect(stream.closedByClient).toBe(true);
    await flushPromises();
    expect(eventStreams()).toHaveLength(1);
  });

  it("keeps one stream across workspace switches and follows the shown console's topic", async () => {
    vi.useFakeTimers({ toFake: ["setInterval", "clearInterval"] });
    const backend = installFakeBackend({
      user: {
        ...merchantUser,
        roles: [ROLES.user, ROLES.merchant, ROLES.rider],
        rider: { rider_id: 7, name: "Alice Rider", on_duty: false, active: true },
      },
    });
    backend.state.afterSalesCases = [
      afterSalesCaseFixture({
        id: 900,
        order_id: 101,
        status: "REFUNDING",
        merchant_decision: "APPROVED",
        approved_amount: 6.39,
        resolution: "MERCHANT_APPROVED",
        deadline_at: null,
        refund: refundFixture({ id: 901, case_id: 900, order_id: 101 }),
      }),
    ];
    const user = renderApp();

    expect(await screen.findByRole("region", { name: "Merchant console" })).toBeInTheDocument();
    const stream = await connectEventStream();
    expect(stream.recipients).toEqual(["customer:21", "restaurant:1", "rider:7"]);

    // The rider console mounts with the stream already open: it reads once, on mount.
    await openWorkspace(user, "Rider console");
    expect(await screen.findByRole("region", { name: "My deliveries" })).toBeInTheDocument();
    await waitFor(() => expect(reads(backend, "/rider/deliveries")).toBe(1));
    act(() => backend.assignDelivery(deliveryFixture({ delivery_id: 6, order_id: 102 })));
    expect(await deliveryCard(6)).toBeInTheDocument();
    expect(reads(backend, "/rider/deliveries")).toBe(2);

    // The hidden ordering page does not follow its signals; showing it reads once.
    await openWorkspace(user, "Order food");
    await waitFor(() => expect(reads(backend, "/notifications")).toBe(1));
    await openWorkspace(user, "Merchant console");
    act(() => backend.addNotification(notice(41, 7, "Order completed")));
    await flushPromises();
    expect(reads(backend, "/notifications")).toBe(1);
    await openWorkspace(user, "Order food");
    await waitFor(() => expect(reads(backend, "/notifications")).toBe(2));

    // The merchant's after-sales inbox follows its own topic: the refund went through.
    await openWorkspace(user, "Merchant console");
    await user.click(await screen.findByRole("button", { name: "After-sales" }));
    await waitFor(() => expect(reads(backend, "/merchant/after-sales")).toBe(1));
    act(() => settleAfterSalesRefund(backend.state, 900));
    await waitFor(() => expect(reads(backend, "/merchant/after-sales")).toBe(2));
    expect(eventStreams()).toHaveLength(1);
  });

  it("ignores signals while the tab is hidden and reads once when it is shown again", async () => {
    vi.useFakeTimers({ toFake: ["setInterval", "clearInterval"] });
    const backend = merchantBackend();
    renderApp();

    await orderCard(7);
    const stream = await connectEventStream();
    await waitFor(() => expect(reads(backend, "/merchant/orders")).toBe(2));

    // A signal while the tab is hidden needs no read: showing the tab reads once anyway.
    setVisibility("hidden");
    act(() => backend.receiveMerchantOrder(merchantOrderFixture({ order_id: 9 })));
    act(() => {
      vi.advanceTimersByTime(PUSH_FALLBACK_POLL_MS);
    });
    await flushPromises();
    expect(reads(backend, "/merchant/orders")).toBe(2);

    setVisibility("visible");
    expect(await orderCard(9)).toBeInTheDocument();
    expect(reads(backend, "/merchant/orders")).toBe(3);
    expect(stream.readyState).toBe(stream.OPEN);
  });
});
