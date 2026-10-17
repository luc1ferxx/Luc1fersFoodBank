import { act, screen, waitFor, within } from "@testing-library/react";
import type { UserEvent } from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import type { OrderStatusChange } from "../api/dto";
import {
  apiError,
  deferred,
  demoUser,
  installFakeBackend,
  json,
  orderFixture,
  orderNoFor,
} from "../test/fakeBackend";
import { flushPromises, renderApp } from "../test/helpers";

const placed: OrderStatusChange = {
  from_status: null,
  to_status: "PAID",
  actor_type: "CUSTOMER",
  actor_id: 1,
  reason: null,
  occurred_at: "2026-10-16T12:00:00Z",
};

const rejected: OrderStatusChange = {
  from_status: "PAID",
  to_status: "CANCELLED",
  actor_type: "MERCHANT",
  actor_id: 42,
  reason: "MERCHANT_REJECTED: Out of buns",
  occurred_at: "2026-10-16T12:05:00Z",
};

const historyName = `Status history of Order #${orderNoFor(7)}`;

const openHistory = async (user: UserEvent) => {
  await screen.findByText(`${demoUser.first_name} is signed in`);
  await user.click(screen.getByRole("button", { name: "Orders" }));
  await user.click(await screen.findByRole("button", { name: "Status history" }));
  return screen.findByRole("region", { name: historyName });
};

const historyLines = (section: HTMLElement) =>
  within(section)
    .queryAllByRole("listitem")
    .map((item) => within(item).getByRole("strong").textContent);

describe("order status history", () => {
  it("lists the order's status changes oldest first with who made them", async () => {
    const backend = installFakeBackend({ user: demoUser });
    backend.state.orders = [
      orderFixture({ id: 7, status: "CANCELLED", cancel_reason: rejected.reason }),
    ];
    backend.state.orderHistory.set(7, [placed, rejected]);
    const user = renderApp();

    const section = await openHistory(user);
    await waitFor(() => expect(within(section).getAllByRole("listitem")).toHaveLength(2));
    const [first, second] = within(section).getAllByRole("listitem");
    expect(first).toHaveTextContent("PAID");
    expect(first).toHaveTextContent("by Customer");
    expect(second).toHaveTextContent("PAID → CANCELLED");
    expect(second).toHaveTextContent("by Restaurant");
    expect(second).toHaveTextContent("Reason: MERCHANT_REJECTED: Out of buns");
    expect(screen.getByText("Cancelled: MERCHANT_REJECTED: Out of buns")).toBeInTheDocument();

    const toggle = screen.getByRole("button", { name: "Hide status history" });
    expect(toggle).toHaveAttribute("aria-expanded", "true");
    await user.click(toggle);
    expect(screen.queryByRole("region", { name: historyName })).toBeNull();
    expect(backend.callsTo("GET", "/orders/7/history")).toHaveLength(1);
  });

  it("shows a load failure and recovers on refresh", async () => {
    const backend = installFakeBackend({ user: demoUser });
    backend.state.orders = [orderFixture({ id: 7, status: "PAID" })];
    backend.state.orderHistory.set(7, [placed]);
    backend.on("GET", "/orders/7/history", () =>
      apiError(500, "History unavailable", { traceId: "trace-71" }),
    );
    const user = renderApp();

    const section = await openHistory(user);
    expect(await within(section).findByRole("alert")).toHaveTextContent(
      "500: History unavailable Trace trace-71.",
    );

    await user.click(within(section).getByRole("button", { name: "Refresh history" }));
    await waitFor(() => expect(within(section).getAllByRole("listitem")).toHaveLength(1));
    expect(within(section).queryByRole("alert")).not.toBeInTheDocument();
  });

  it("reloads after the order changes and drops the superseded response", async () => {
    const backend = installFakeBackend({ user: demoUser });
    backend.state.orders = [orderFixture({ id: 7, status: "PAID" })];
    backend.state.orderHistory.set(7, [placed]);
    const slowFirstLoad = deferred();
    backend.on("GET", "/orders/7/history", () => slowFirstLoad.promise);
    const user = renderApp();

    const section = await openHistory(user);
    expect(within(section).getByRole("button", { name: "Loading..." })).toBeDisabled();

    // The customer cancels while the first history request is still running.
    await user.click(screen.getByRole("button", { name: "Cancel order" }));
    await waitFor(() => expect(historyLines(section)).toEqual(["PAID", "PAID → CANCELLED"]));

    // The first request's late answer (without the cancel) must not replace the newer one.
    await act(async () => {
      slowFirstLoad.resolve(json([placed]));
      await slowFirstLoad.promise;
    });
    await flushPromises();
    expect(historyLines(section)).toEqual(["PAID", "PAID → CANCELLED"]);
    expect(backend.callsTo("GET", "/orders/7/history")[0]?.signal?.aborted).toBe(true);
  });

  it("says when nothing was recorded yet", async () => {
    const backend = installFakeBackend({ user: demoUser });
    backend.state.orders = [orderFixture({ id: 7, status: "PAID" })];
    const user = renderApp();

    const section = await openHistory(user);
    expect(await within(section).findByText("No status changes recorded yet.")).toBeInTheDocument();
  });
});
