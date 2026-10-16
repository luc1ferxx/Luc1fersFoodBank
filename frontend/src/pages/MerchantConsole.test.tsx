import { act, screen, waitFor, within } from "@testing-library/react";
import type { UserEvent } from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { MERCHANT_POLL_MS } from "../hooks/useMerchantOrders";
import {
  apiError,
  deferred,
  idempotencyKeys,
  installFakeBackend,
  json,
  merchantOrderFixture,
  merchantUser,
  requestJson,
} from "../test/fakeBackend";
import { flushPromises, renderApp, resetVisibility, setVisibility } from "../test/helpers";

const card = (orderId: number) => screen.findByRole("article", { name: `Order #${orderId}` });

const merchantBackend = () => {
  const backend = installFakeBackend({ user: merchantUser });
  backend.state.merchantOrders = [
    merchantOrderFixture({ order_id: 7, status: "PAID" }),
    merchantOrderFixture({ order_id: 8, status: "ACCEPTED", total_price: 4.5 }),
  ];
  return backend;
};

const ordersRequests = (backend: ReturnType<typeof installFakeBackend>) =>
  backend.callsTo("GET", "/merchant/orders");

const selectStatus = (user: UserEvent, label: string) =>
  user.selectOptions(screen.getByLabelText("Status"), label);

describe("merchant console", () => {
  afterEach(() => {
    resetVisibility();
    vi.useRealTimers();
  });

  it("lists the restaurant's orders and filters them by status", async () => {
    const backend = merchantBackend();
    const user = renderApp();

    expect(await screen.findByRole("heading", { name: "Burger Barn orders" })).toBeInTheDocument();
    const paid = await card(7);
    expect(paid).toHaveTextContent("2 x Whopper · $12.78");
    expect(within(paid).getByRole("button", { name: "Accept" })).toBeEnabled();
    expect(within(await card(8)).getByRole("button", { name: "Mark preparing" })).toBeEnabled();
    expect(ordersRequests(backend)[0]?.url).toBe("/merchant/orders?limit=50");

    await selectStatus(user, "Accepted");
    await waitFor(() => expect(screen.queryByRole("article", { name: "Order #7" })).toBeNull());
    expect(await card(8)).toBeInTheDocument();
    expect(ordersRequests(backend).at(-1)?.url).toBe("/merchant/orders?limit=50&status=ACCEPTED");

    await selectStatus(user, "Completed");
    expect(await screen.findByText("No completed orders.")).toBeInTheDocument();
  });

  it("accepts an order and then marks it as preparing", async () => {
    const backend = merchantBackend();
    const user = renderApp();

    await user.click(within(await card(7)).getByRole("button", { name: "Accept" }));

    expect(await screen.findByText("Order #7 accepted.")).toBeInTheDocument();
    const accepted = await card(7);
    expect(within(accepted).getByText("ACCEPTED")).toBeInTheDocument();
    await user.click(within(accepted).getByRole("button", { name: "Mark preparing" }));

    expect(await screen.findByText("Order #7 marked as preparing.")).toBeInTheDocument();
    expect(
      within(await card(7)).getByText("Waiting for a rider to pick it up."),
    ).toBeInTheDocument();
    expect(backend.callsTo("POST", "/merchant/orders/7/accept")).toHaveLength(1);
    expect(backend.callsTo("POST", "/merchant/orders/7/preparing")).toHaveLength(1);
    // The commands are idempotent by state on the server and carry no key.
    expect(idempotencyKeys(backend.callsTo("POST", "/merchant/orders/7/accept"))).toEqual([null]);
  });

  it("rejects an order only with a reason", async () => {
    const backend = merchantBackend();
    const user = renderApp();

    const paid = await card(7);
    await user.click(within(paid).getByRole("button", { name: "Reject" }));
    await user.click(within(paid).getByRole("button", { name: "Reject order" }));
    expect(within(paid).getByRole("alert")).toHaveTextContent(
      "Give the customer a reason for the rejection.",
    );
    expect(backend.callsTo("POST", "/merchant/orders/7/reject")).toHaveLength(0);

    await user.type(
      within(paid).getByLabelText("Reason for rejecting order #7"),
      "  Out of buns  ",
    );
    await user.click(within(paid).getByRole("button", { name: "Reject order" }));

    expect(await screen.findByText("Order #7 rejected.")).toBeInTheDocument();
    expect(requestJson(backend.callsTo("POST", "/merchant/orders/7/reject")[0])).toEqual({
      reason: "Out of buns",
    });
    const rejected = await card(7);
    expect(within(rejected).getByText("CANCELLED")).toBeInTheDocument();
    expect(rejected).toHaveTextContent("Cancelled: MERCHANT_REJECTED: Out of buns");
    expect(within(rejected).queryByRole("button", { name: "Accept" })).toBeNull();
  });

  it("goes back from the reject form without calling the backend", async () => {
    const backend = merchantBackend();
    const user = renderApp();

    const paid = await card(7);
    await user.click(within(paid).getByRole("button", { name: "Reject" }));
    await user.click(within(paid).getByRole("button", { name: "Back" }));

    expect(within(paid).getByRole("button", { name: "Accept" })).toBeInTheDocument();
    expect(backend.callsTo("POST", "/merchant/orders/7/reject")).toHaveLength(0);
  });

  it("shows a failed command on its order and lets the merchant retry", async () => {
    const backend = merchantBackend();
    backend.on("POST", "/merchant/orders/7/accept", () =>
      apiError(503, "Order service unavailable", { traceId: "trace-81" }),
    );
    const user = renderApp();

    const paid = await card(7);
    await user.click(within(paid).getByRole("button", { name: "Accept" }));
    expect(await within(paid).findByRole("alert")).toHaveTextContent(
      "503: Order service unavailable Trace trace-81.",
    );
    expect(within(await card(8)).queryByRole("alert")).toBeNull();

    await user.click(within(paid).getByRole("button", { name: "Accept" }));
    expect(await screen.findByText("Order #7 accepted.")).toBeInTheDocument();
    expect(within(await card(7)).queryByRole("alert")).toBeNull();
  });

  it("re-reads the list when an order changed meanwhile (409)", async () => {
    const backend = merchantBackend();
    const user = renderApp();

    const paid = await card(7);
    // The customer cancels before the merchant decides.
    const order = backend.state.merchantOrders[0];
    if (!order) {
      throw new Error("missing fixture");
    }
    order.status = "CANCELLED";
    order.cancel_reason = "CUSTOMER_CANCELLED";
    const readsBefore = ordersRequests(backend).length;

    await user.click(within(paid).getByRole("button", { name: "Accept" }));

    expect(await within(paid).findByRole("alert")).toHaveTextContent(
      "409: Invalid order status transition: CANCELLED -> ACCEPTED",
    );
    await waitFor(() => expect(within(paid).getByText("CANCELLED")).toBeInTheDocument());
    expect(ordersRequests(backend).length).toBe(readsBefore + 1);
    expect(within(paid).queryByRole("button", { name: "Accept" })).toBeNull();
  });

  it("shows a load failure and recovers on Refresh", async () => {
    const backend = merchantBackend();
    backend.on("GET", "/merchant/orders", () =>
      apiError(500, "Inbox unavailable", { traceId: "trace-82" }),
    );
    const user = renderApp();

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "500: Inbox unavailable Trace trace-82.",
    );
    expect(screen.queryByText("Loading orders...")).toBeNull();

    await user.click(screen.getByRole("button", { name: "Refresh" }));
    expect(await card(7)).toBeInTheDocument();
    expect(screen.queryByRole("alert")).toBeNull();
  });

  it("drops the response for a status filter the merchant already left", async () => {
    const backend = merchantBackend();
    const slowPaid = deferred();
    backend.on(
      "GET",
      "/merchant/orders",
      (request) => {
        const status = request.query.get("status");
        if (status === "PAID") {
          return slowPaid.promise;
        }
        return json(
          backend.state.merchantOrders.filter((order) => !status || order.status === status),
        );
      },
      { once: false },
    );
    const user = renderApp();

    await card(7);
    await selectStatus(user, "New (paid)");
    await selectStatus(user, "Accepted");
    expect(await card(8)).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByRole("article", { name: "Order #7" })).toBeNull());

    // The PAID list arrives after the merchant switched to ACCEPTED.
    await act(async () => {
      slowPaid.resolve(json([merchantOrderFixture({ order_id: 7 })]));
      await slowPaid.promise;
    });
    await flushPromises();

    expect(screen.queryByRole("article", { name: "Order #7" })).toBeNull();
    expect(screen.getByRole("article", { name: "Order #8" })).toBeInTheDocument();
    expect(screen.getByLabelText("Status")).toHaveValue("ACCEPTED");
    expect(
      ordersRequests(backend).find((call) => call.query.get("status") === "PAID")?.signal?.aborted,
    ).toBe(true);
  });

  it("drops a list read that started before a command finished", async () => {
    vi.useFakeTimers({ toFake: ["setInterval", "clearInterval"] });
    const backend = merchantBackend();
    const user = renderApp();

    const paid = await card(7);
    // A poll starts while the order is still PAID and answers late.
    const stalePoll = deferred();
    backend.on("GET", "/merchant/orders", () => stalePoll.promise);
    act(() => {
      vi.advanceTimersByTime(MERCHANT_POLL_MS);
    });
    await waitFor(() => expect(ordersRequests(backend)).toHaveLength(2));

    await user.click(within(paid).getByRole("button", { name: "Accept" }));
    expect(await screen.findByText("Order #7 accepted.")).toBeInTheDocument();

    await act(async () => {
      stalePoll.resolve(json([merchantOrderFixture({ order_id: 7, status: "PAID" })]));
      await stalePoll.promise;
    });
    await flushPromises();

    expect(within(paid).getByText("ACCEPTED")).toBeInTheDocument();
    expect(within(paid).queryByRole("button", { name: "Accept" })).toBeNull();
    expect(ordersRequests(backend)[1]?.signal?.aborted).toBe(true);
  });

  it("polls every 15 seconds only while the tab is visible", async () => {
    vi.useFakeTimers({ toFake: ["setInterval", "clearInterval"] });
    const backend = merchantBackend();
    renderApp();

    await card(7);
    expect(ordersRequests(backend)).toHaveLength(1);

    act(() => {
      vi.advanceTimersByTime(MERCHANT_POLL_MS);
    });
    await waitFor(() => expect(ordersRequests(backend)).toHaveLength(2));

    setVisibility("hidden");
    act(() => {
      vi.advanceTimersByTime(MERCHANT_POLL_MS * 4);
    });
    await flushPromises();
    expect(ordersRequests(backend)).toHaveLength(2);

    // Coming back catches up at once, then polling resumes.
    setVisibility("visible");
    await waitFor(() => expect(ordersRequests(backend)).toHaveLength(3));
    act(() => {
      vi.advanceTimersByTime(MERCHANT_POLL_MS);
    });
    await waitFor(() => expect(ordersRequests(backend)).toHaveLength(4));
  });

  it("stops polling when auto-refresh is turned off", async () => {
    vi.useFakeTimers({ toFake: ["setInterval", "clearInterval"] });
    const backend = merchantBackend();
    const user = renderApp();

    await card(7);
    await user.click(screen.getByLabelText("Refresh every 15 seconds"));
    act(() => {
      vi.advanceTimersByTime(MERCHANT_POLL_MS * 3);
    });
    await flushPromises();
    expect(ordersRequests(backend)).toHaveLength(1);
  });

  it("explains a deactivated merchant account", async () => {
    installFakeBackend({
      user: {
        ...merchantUser,
        merchant: {
          merchant_id: 3,
          restaurant_id: 1,
          restaurant_name: "Burger Barn",
          active: false,
        },
      },
    });
    renderApp();

    expect(
      await screen.findByText(
        "This merchant account is deactivated. Ask an admin to reactivate it.",
      ),
    ).toBeInTheDocument();
  });
});
