import { act, screen, waitFor, within } from "@testing-library/react";
import type { UserEvent } from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { currencyCode } from "../api/currency";
import { MERCHANT_POLL_MS } from "../hooks/useMerchantOrders";
import type { AfterSalesStatus } from "../api/dto";
import {
  USD,
  afterSalesCaseFixture,
  apiError,
  deferred,
  idempotencyKeys,
  installFakeBackend,
  json,
  merchantOrderFixture,
  merchantUser,
  orderNoFor,
  requestJson,
} from "../test/fakeBackend";
import { flushPromises, renderApp, resetVisibility, setVisibility } from "../test/helpers";

const DEACTIVATED = "This merchant account is deactivated. Ask an admin to reactivate it.";

const card = (orderId: number) => screen.findByRole("article", { name: `Order #${orderId}` });

/** The merchant login as GET /me shows it once an admin (de)activated the account. */
const withMerchantActive = (active: boolean) => ({
  ...merchantUser,
  merchant: { merchant_id: 3, restaurant_id: 1, restaurant_name: "Burger Barn", active },
});

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

  it("explains a deactivated merchant account instead of showing the 403", async () => {
    const backend = installFakeBackend({ user: withMerchantActive(false) });
    renderApp();

    expect(await screen.findByText(DEACTIVATED)).toBeInTheDocument();
    await waitFor(() => expect(ordersRequests(backend)).toHaveLength(1));
    await flushPromises();
    expect(screen.queryByRole("alert")).toBeNull();
    expect(screen.queryByText("Loading orders...")).toBeNull();
  });

  it("stops polling once the account is deactivated and recovers on Refresh", async () => {
    vi.useFakeTimers({ toFake: ["setInterval", "clearInterval"] });
    const backend = merchantBackend();
    const user = renderApp();

    const paid = await card(7);
    // An admin deactivates the merchant; the backend answers 403 from the next request.
    backend.state.user = withMerchantActive(false);
    act(() => {
      vi.advanceTimersByTime(MERCHANT_POLL_MS);
    });

    expect(await screen.findByText(DEACTIVATED)).toBeInTheDocument();
    expect(screen.queryByRole("alert")).toBeNull();
    expect(within(paid).getByRole("button", { name: "Accept" })).toBeDisabled();
    expect(within(paid).getByRole("button", { name: "Reject" })).toBeDisabled();

    // The notice can be on screen before React ran the cleanup that clears the poll
    // interval (the 403 rendered outside act()); a tick in that gap must not poll either.
    act(() => {
      vi.advanceTimersByTime(MERCHANT_POLL_MS * 3);
    });
    await flushPromises();
    expect(ordersRequests(backend)).toHaveLength(2);
    expect(screen.getByLabelText("Refresh every 15 seconds")).toBeDisabled();

    backend.state.user = withMerchantActive(true);
    await user.click(screen.getByRole("button", { name: "Refresh" }));
    await waitFor(() => expect(screen.queryByText(DEACTIVATED)).toBeNull());
    expect(within(await card(7)).getByRole("button", { name: "Accept" })).toBeEnabled();
    act(() => {
      vi.advanceTimersByTime(MERCHANT_POLL_MS);
    });
    await waitFor(() => expect(ordersRequests(backend)).toHaveLength(4));
  });

  it("re-reads the list when a command comes back 403", async () => {
    const backend = merchantBackend();
    const user = renderApp();

    const paid = await card(7);
    backend.state.user = withMerchantActive(false);
    await user.click(within(paid).getByRole("button", { name: "Accept" }));

    expect(await screen.findByText(DEACTIVATED)).toBeInTheDocument();
    expect(within(paid).getByRole("alert")).toHaveTextContent("403: Access denied");
    expect(backend.state.merchantOrders[0]?.status).toBe("PAID");
  });
});

describe("merchant after-sales", () => {
  afterEach(() => {
    resetVisibility();
    vi.useRealTimers();
  });

  /** One request waiting for the merchant (order 101) and one already refunded (order 102). */
  const afterSalesBackend = () => {
    const backend = installFakeBackend({ user: merchantUser });
    backend.state.afterSalesCases = [
      afterSalesCaseFixture({ id: 900, order_id: 101 }),
      afterSalesCaseFixture({
        id: 901,
        order_id: 102,
        status: "REFUNDED",
        merchant_decision: "APPROVED",
        approved_amount: 6.39,
        refunded_amount: 6.39,
        resolution: "MERCHANT_APPROVED",
        deadline_at: null,
      }),
    ];
    return backend;
  };

  /** A row of GET /merchant/after-sales as the backend sends it, for a stale response. */
  const merchantCaseJson = (caseId: number, orderId: number, status: AfterSalesStatus) => ({
    case_id: caseId,
    order_id: orderId,
    order_no: orderNoFor(orderId),
    status,
    reason_code: "QUALITY_ISSUE",
    description: "The burger was cold.",
    lines: [],
    requested_amount: 6.39,
    currency: USD,
    deadline_at: "2099-01-01T00:00:00",
    opened_at: "2026-10-20T12:00:00",
    updated_at: "2026-10-20T12:00:00",
  });

  const caseName = (orderId: number) => `Refund request for Order #${orderNoFor(orderId)}`;

  const requestCard = (orderId: number) =>
    screen.findByRole("article", { name: caseName(orderId) });

  const caseRequests = (backend: ReturnType<typeof installFakeBackend>) =>
    backend.callsTo("GET", "/merchant/after-sales");

  const openAfterSales = async (user: UserEvent) => {
    await user.click(await screen.findByRole("button", { name: "After-sales" }));
    expect(
      await screen.findByRole("heading", { name: "Burger Barn refund requests" }),
    ).toBeInTheDocument();
  };

  it("lists the restaurant's refund requests on their own tab and filters them by status", async () => {
    const backend = afterSalesBackend();
    const user = renderApp();

    await openAfterSales(user);
    const waiting = await requestCard(101);
    expect(waiting).toHaveTextContent("Waiting for the restaurant");
    expect(waiting).toHaveTextContent("$6.39 requested");
    expect(waiting).toHaveTextContent("1 x Whopper · $6.39");
    expect(waiting).toHaveTextContent("Quality issue: The burger was cold.");
    expect(within(waiting).getByText(/^Your answer due in 23h 5\dm/)).toBeInTheDocument();
    expect(within(waiting).getByRole("button", { name: "Approve refund" })).toBeEnabled();
    const refunded = await requestCard(102);
    expect(refunded).toHaveTextContent("Refunded");
    expect(refunded).toHaveTextContent("Refund$6.39");
    expect(within(refunded).queryByRole("button", { name: "Approve refund" })).toBeNull();
    expect(caseRequests(backend)[0]?.url).toBe("/merchant/after-sales?limit=50");
    // Only the shown section reads: the orders tab is not mounted any more.
    expect(backend.callsTo("GET", "/merchant/orders")).toHaveLength(1);

    await selectStatus(user, "Refunded");
    await waitFor(() => expect(screen.queryByRole("article", { name: caseName(101) })).toBeNull());
    expect(await requestCard(102)).toBeInTheDocument();
    expect(caseRequests(backend).at(-1)?.url).toBe(
      "/merchant/after-sales?limit=50&status=REFUNDED",
    );

    await selectStatus(user, "Platform reviewing");
    expect(
      await screen.findByText('No refund requests in "Platform reviewing".'),
    ).toBeInTheDocument();
  });

  it("approves a refund request without an Idempotency-Key", async () => {
    const backend = afterSalesBackend();
    const user = renderApp();

    await openAfterSales(user);
    await user.click(
      within(await requestCard(101)).getByRole("button", { name: "Approve refund" }),
    );

    expect(
      await screen.findByText(`Order #${orderNoFor(101)}: refund of $6.39 approved.`),
    ).toBeInTheDocument();
    const approved = await requestCard(101);
    expect(approved).toHaveTextContent("Refunding");
    expect(approved).toHaveTextContent("Refund$6.39");
    expect(within(approved).queryByRole("button", { name: "Approve refund" })).toBeNull();
    // The merchant's answers are idempotent by state on the server and carry no key.
    expect(idempotencyKeys(backend.callsTo("POST", "/merchant/after-sales/900/approve"))).toEqual([
      null,
    ]);
  });

  it("rejects a refund request only with a reason", async () => {
    const backend = afterSalesBackend();
    const user = renderApp();

    await openAfterSales(user);
    const waiting = await requestCard(101);
    await user.click(within(waiting).getByRole("button", { name: "Reject" }));
    await user.click(within(waiting).getByRole("button", { name: "Reject request" }));
    expect(within(waiting).getByRole("alert")).toHaveTextContent(
      "Tell the customer why you reject the request.",
    );
    expect(backend.callsTo("POST", "/merchant/after-sales/900/reject")).toHaveLength(0);

    await user.type(
      within(waiting).getByLabelText(
        `Reason for rejecting the refund request of Order #${orderNoFor(101)}`,
      ),
      "  It left the kitchen hot  ",
    );
    await user.click(within(waiting).getByRole("button", { name: "Reject request" }));

    expect(
      await screen.findByText(`Order #${orderNoFor(101)}: refund request rejected.`),
    ).toBeInTheDocument();
    const calls = backend.callsTo("POST", "/merchant/after-sales/900/reject");
    expect(calls.map(requestJson)).toEqual([{ reason: "It left the kitchen hot" }]);
    expect(idempotencyKeys(calls)).toEqual([null]);
    const rejected = await requestCard(101);
    expect(rejected).toHaveTextContent("Not agreed");
    expect(rejected).toHaveTextContent("Your noteIt left the kitchen hot");
    // 48 hours from the rejection; the countdown's clock may lag the fake's by a tick.
    expect(
      within(rejected).getByText(/^Customer can escalate in (1d 23h|2d 0h)/),
    ).toBeInTheDocument();
    expect(within(rejected).queryByRole("button", { name: "Reject" })).toBeNull();
  });

  it("offers a partial refund only below the requested amount", async () => {
    const backend = afterSalesBackend();
    const user = renderApp();

    await openAfterSales(user);
    const waiting = await requestCard(101);
    await user.click(within(waiting).getByRole("button", { name: "Offer partial refund" }));
    const amount = within(waiting).getByLabelText(`Partial refund for Order #${orderNoFor(101)}`);

    await user.type(amount, "6.39");
    await user.click(within(waiting).getByRole("button", { name: "Send offer" }));
    expect(within(waiting).getByRole("alert")).toHaveTextContent(
      "Offer more than $0.00 and less than the requested $6.39.",
    );
    await user.clear(amount);
    await user.type(amount, "3.505");
    await user.click(within(waiting).getByRole("button", { name: "Send offer" }));
    expect(within(waiting).getByRole("alert")).toHaveTextContent(
      "Enter the amount with at most two decimals, like 4.50.",
    );
    expect(backend.callsTo("POST", "/merchant/after-sales/900/offer")).toHaveLength(0);

    await user.clear(amount);
    await user.type(amount, "3.5");
    await user.type(
      within(waiting).getByLabelText("Note for the customer (optional)"),
      "Sorry about that",
    );
    await user.click(within(waiting).getByRole("button", { name: "Send offer" }));

    expect(
      await screen.findByText(`Order #${orderNoFor(101)}: partial refund of $3.50 offered.`),
    ).toBeInTheDocument();
    const calls = backend.callsTo("POST", "/merchant/after-sales/900/offer");
    expect(calls.map(requestJson)).toEqual([{ amount: 3.5, note: "Sorry about that" }]);
    expect(idempotencyKeys(calls)).toEqual([null]);
    const offered = await requestCard(101);
    expect(offered).toHaveTextContent("Partial refund offered");
    expect(offered).toHaveTextContent("Offered$3.50");
    expect(within(offered).getByText(/^Customer's answer due in/)).toBeInTheDocument();
  });

  it("offers only whole amounts for a case in a currency without minor unit", async () => {
    const jpy = currencyCode("JPY");
    const backend = installFakeBackend({ user: merchantUser });
    backend.state.afterSalesCases = [
      afterSalesCaseFixture({
        id: 900,
        order_id: 101,
        requested_amount: 950,
        currency: jpy,
        lines: [
          {
            order_item_id: 1,
            menu_item_name: "Whopper",
            quantity: 1,
            unit_price: 950,
            amount: 950,
            currency: jpy,
          },
        ],
      }),
    ];
    const user = renderApp();

    await openAfterSales(user);
    const waiting = await requestCard(101);
    await user.click(within(waiting).getByRole("button", { name: "Offer partial refund" }));
    const amount = within(waiting).getByLabelText(`Partial refund for Order #${orderNoFor(101)}`);

    // The backend refuses decimals the yen does not have (400).
    await user.type(amount, "4.50");
    await user.click(within(waiting).getByRole("button", { name: "Send offer" }));
    expect(within(waiting).getByRole("alert")).toHaveTextContent(
      "Enter the amount as a whole number, like 5.",
    );
    expect(backend.callsTo("POST", "/merchant/after-sales/900/offer")).toHaveLength(0);

    await user.clear(amount);
    await user.type(amount, "450");
    await user.click(within(waiting).getByRole("button", { name: "Send offer" }));

    expect(
      await screen.findByText(`Order #${orderNoFor(101)}: partial refund of ¥450 offered.`),
    ).toBeInTheDocument();
    const calls = backend.callsTo("POST", "/merchant/after-sales/900/offer");
    expect(calls.map(requestJson)).toEqual([{ amount: 450 }]);
  });

  it("re-reads the requests when the case moved on meanwhile (409)", async () => {
    const backend = afterSalesBackend();
    const user = renderApp();

    await openAfterSales(user);
    const waiting = await requestCard(101);
    // The customer withdraws before the merchant answers.
    const current = backend.state.afterSalesCases[0];
    if (!current) {
      throw new Error("missing fixture");
    }
    current.status = "CLOSED";
    current.close_reason = "WITHDRAWN";
    current.deadline_at = null;
    const readsBefore = caseRequests(backend).length;

    await user.click(within(waiting).getByRole("button", { name: "Approve refund" }));

    expect(await within(waiting).findByRole("alert")).toHaveTextContent(
      "409: After-sales case is CLOSED; only PENDING_MERCHANT cases can be approved",
    );
    await waitFor(() => expect(within(waiting).getByText("Closed")).toBeInTheDocument());
    expect(caseRequests(backend).length).toBe(readsBefore + 1);
    expect(within(waiting).queryByRole("button", { name: "Approve refund" })).toBeNull();
  });

  it("disables the answers once the merchant's deadline passed", async () => {
    const backend = installFakeBackend({ user: merchantUser });
    backend.state.afterSalesCases = [
      afterSalesCaseFixture({ id: 900, order_id: 101, deadline_at: "2026-01-01T00:00:00" }),
    ];
    const user = renderApp();

    await openAfterSales(user);
    const late = await requestCard(101);
    expect(late).toHaveTextContent("Deadline passed");
    expect(late).toHaveTextContent(
      "The deadline passed: the requested amount is refunded automatically.",
    );
    ["Approve refund", "Offer partial refund", "Reject"].forEach((name) =>
      expect(within(late).getByRole("button", { name })).toBeDisabled(),
    );
  });

  it("stops polling the requests once the account is deactivated and recovers on Refresh", async () => {
    vi.useFakeTimers({ toFake: ["setInterval", "clearInterval"] });
    const backend = afterSalesBackend();
    const user = renderApp();

    await openAfterSales(user);
    const waiting = await requestCard(101);
    expect(caseRequests(backend)).toHaveLength(1);
    // An admin deactivates the merchant; the backend answers 403 from the next request.
    backend.state.user = withMerchantActive(false);
    act(() => {
      vi.advanceTimersByTime(MERCHANT_POLL_MS);
    });

    expect(await screen.findByText(DEACTIVATED)).toBeInTheDocument();
    expect(screen.queryByRole("alert")).toBeNull();
    expect(within(waiting).getByRole("button", { name: "Approve refund" })).toBeDisabled();
    act(() => {
      vi.advanceTimersByTime(MERCHANT_POLL_MS * 3);
    });
    await flushPromises();
    expect(caseRequests(backend)).toHaveLength(2);
    expect(screen.getByLabelText("Refresh every 15 seconds")).toBeDisabled();

    backend.state.user = withMerchantActive(true);
    await user.click(screen.getByRole("button", { name: "Refresh" }));
    await waitFor(() => expect(screen.queryByText(DEACTIVATED)).toBeNull());
    expect(
      within(await requestCard(101)).getByRole("button", { name: "Approve refund" }),
    ).toBeEnabled();
    act(() => {
      vi.advanceTimersByTime(MERCHANT_POLL_MS);
    });
    await waitFor(() => expect(caseRequests(backend)).toHaveLength(4));
  });

  it("drops the requests for a status filter the merchant already left", async () => {
    const backend = afterSalesBackend();
    const user = renderApp();

    await openAfterSales(user);
    await requestCard(101);
    const slowRefunded = deferred();
    backend.on("GET", "/merchant/after-sales", () => slowRefunded.promise);
    await selectStatus(user, "Refunded");
    await selectStatus(user, "Waiting for the restaurant");
    expect(await requestCard(101)).toBeInTheDocument();

    // The REFUNDED list arrives after the merchant switched to PENDING_MERCHANT.
    await act(async () => {
      slowRefunded.resolve(json([merchantCaseJson(901, 102, "REFUNDED")]));
      await slowRefunded.promise;
    });
    await flushPromises();

    expect(screen.queryByRole("article", { name: caseName(102) })).toBeNull();
    expect(screen.getByLabelText("Status")).toHaveValue("PENDING_MERCHANT");
    expect(
      caseRequests(backend).find((call) => call.query.get("status") === "REFUNDED")?.signal
        ?.aborted,
    ).toBe(true);
  });

  it("drops a list read that started before an answer finished", async () => {
    vi.useFakeTimers({ toFake: ["setInterval", "clearInterval"] });
    const backend = afterSalesBackend();
    const user = renderApp();

    await openAfterSales(user);
    const waiting = await requestCard(101);
    // A poll starts while the request still waits for the merchant and answers late.
    const stalePoll = deferred();
    backend.on("GET", "/merchant/after-sales", () => stalePoll.promise);
    act(() => {
      vi.advanceTimersByTime(MERCHANT_POLL_MS);
    });
    await waitFor(() => expect(caseRequests(backend)).toHaveLength(2));

    await user.click(within(waiting).getByRole("button", { name: "Approve refund" }));
    expect(
      await screen.findByText(`Order #${orderNoFor(101)}: refund of $6.39 approved.`),
    ).toBeInTheDocument();

    await act(async () => {
      stalePoll.resolve(json([merchantCaseJson(900, 101, "PENDING_MERCHANT")]));
      await stalePoll.promise;
    });
    await flushPromises();

    expect(within(waiting).getByText("Refunding")).toBeInTheDocument();
    expect(within(waiting).queryByRole("button", { name: "Approve refund" })).toBeNull();
    expect(caseRequests(backend)[1]?.signal?.aborted).toBe(true);
  });
});
