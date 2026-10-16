import { act, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { RIDER_POLL_MS } from "../hooks/useRiderConsole";
import {
  apiError,
  deliveryFixture,
  idempotencyKeys,
  installFakeBackend,
  requestJson,
  riderUser,
} from "../test/fakeBackend";
import { flushPromises, renderApp, resetVisibility, setVisibility } from "../test/helpers";

const deliveryCard = (deliveryId: number) =>
  screen.findByRole("article", { name: `Delivery #${deliveryId}` });

const riderBackend = () => {
  const backend = installFakeBackend({ user: riderUser });
  backend.state.deliveries = [deliveryFixture({ delivery_id: 5, order_id: 101 })];
  if (backend.state.riderProfile) {
    backend.state.riderProfile.active_deliveries = 1;
  }
  return backend;
};

describe("rider console", () => {
  afterEach(() => {
    resetVisibility();
    vi.useRealTimers();
  });

  it("goes on and off duty", async () => {
    const backend = riderBackend();
    const user = renderApp();

    const onDuty = await screen.findByRole("switch", { name: "On duty" });
    expect(onDuty).not.toBeChecked();
    expect(screen.getByText("Go on duty to receive deliveries.")).toBeInTheDocument();

    await user.click(onDuty);
    await waitFor(() => expect(screen.getByRole("switch", { name: "On duty" })).toBeChecked());
    expect(screen.getByText("You can be assigned new deliveries.")).toBeInTheDocument();

    await user.click(screen.getByRole("switch", { name: "On duty" }));
    await waitFor(() => expect(screen.getByRole("switch", { name: "On duty" })).not.toBeChecked());

    expect(backend.callsTo("PUT", "/rider/me/availability").map(requestJson)).toEqual([
      { on_duty: true },
      { on_duty: false },
    ]);
  });

  it("keeps the switch as it was when the availability change fails", async () => {
    const backend = riderBackend();
    backend.on("PUT", "/rider/me/availability", () =>
      apiError(503, "Dispatch unavailable", { traceId: "trace-91" }),
    );
    const user = renderApp();

    await user.click(await screen.findByRole("switch", { name: "On duty" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "503: Dispatch unavailable Trace trace-91.",
    );
    expect(screen.getByRole("switch", { name: "On duty" })).not.toBeChecked();
    expect(screen.getByRole("switch", { name: "On duty" })).toBeEnabled();
  });

  it("picks up and delivers an assigned delivery", async () => {
    const backend = riderBackend();
    const user = renderApp();

    const delivery = await deliveryCard(5);
    expect(delivery).toHaveTextContent("Order #101");
    expect(delivery).toHaveTextContent("Pick up at Burger Barn");
    expect(await screen.findByText("1 active deliveries")).toBeInTheDocument();

    await user.click(within(delivery).getByRole("button", { name: "Picked up" }));
    expect(await screen.findByText("Order #101 picked up.")).toBeInTheDocument();
    expect(
      within(await deliveryCard(5)).getByText("Picked up", { selector: ".status-badge" }),
    ).toBeInTheDocument();

    await user.click(within(await deliveryCard(5)).getByRole("button", { name: "Delivered" }));
    expect(await screen.findByText("Order #101 delivered.")).toBeInTheDocument();
    // Delivered is not an active delivery: the re-read list drops it, and the count follows.
    expect(
      await screen.findByText("No active deliveries. New assignments show up here."),
    ).toBeInTheDocument();
    expect(await screen.findByText("0 active deliveries")).toBeInTheDocument();

    expect(backend.callsTo("POST", "/rider/deliveries/5/picked-up")).toHaveLength(1);
    expect(idempotencyKeys(backend.callsTo("POST", "/rider/deliveries/5/delivered"))).toEqual([
      null,
    ]);
  });

  it("shows delivered deliveries under their filter", async () => {
    const backend = riderBackend();
    backend.state.deliveries.push(
      deliveryFixture({ delivery_id: 4, order_id: 99, status: "DELIVERED" }),
    );
    const user = renderApp();

    await deliveryCard(5);
    await user.selectOptions(screen.getByLabelText("Show"), "Delivered");

    expect(await deliveryCard(4)).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByRole("article", { name: "Delivery #5" })).toBeNull());
    expect(backend.callsTo("GET", "/rider/deliveries").at(-1)?.url).toBe(
      "/rider/deliveries?status=DELIVERED",
    );
  });

  it("explains a wrong-state command and re-reads the deliveries", async () => {
    const backend = riderBackend();
    const user = renderApp();

    const delivery = await deliveryCard(5);
    // The order was cancelled meanwhile, so the delivery is no longer active.
    const stored = backend.state.deliveries[0];
    if (!stored) {
      throw new Error("missing fixture");
    }
    stored.status = "CANCELLED";

    await user.click(within(delivery).getByRole("button", { name: "Picked up" }));

    expect(await within(delivery).findByRole("alert")).toHaveTextContent(
      "409: Invalid delivery status transition: CANCELLED -> PICKED_UP",
    );
    expect(
      await screen.findByText("No active deliveries. New assignments show up here."),
    ).toBeInTheDocument();
  });

  it("reports another rider's delivery as not found", async () => {
    const backend = riderBackend();
    const user = renderApp();

    const delivery = await deliveryCard(5);
    backend.on("POST", "/rider/deliveries/5/picked-up", () =>
      apiError(404, "Delivery not found", { code: "NOT_FOUND" }),
    );
    await user.click(within(delivery).getByRole("button", { name: "Picked up" }));

    expect(await within(delivery).findByRole("alert")).toHaveTextContent("404: Delivery not found");
  });

  it("polls profile and deliveries while visible and pauses while hidden", async () => {
    vi.useFakeTimers({ toFake: ["setInterval", "clearInterval"] });
    const backend = riderBackend();
    renderApp();

    await deliveryCard(5);
    const reads = () => backend.callsTo("GET", "/rider/deliveries").length;
    expect(reads()).toBe(1);

    // A new assignment arrives from the dispatch module.
    backend.state.deliveries.push(deliveryFixture({ delivery_id: 6, order_id: 102 }));
    act(() => {
      vi.advanceTimersByTime(RIDER_POLL_MS);
    });
    expect(await deliveryCard(6)).toBeInTheDocument();
    expect(backend.callsTo("GET", "/rider/me")).toHaveLength(2);

    setVisibility("hidden");
    act(() => {
      vi.advanceTimersByTime(RIDER_POLL_MS * 3);
    });
    await flushPromises();
    expect(reads()).toBe(2);
  });

  it("disables the switch for a deactivated rider", async () => {
    const backend = riderBackend();
    if (backend.state.riderProfile) {
      backend.state.riderProfile.active = false;
    }
    renderApp();

    expect(
      await screen.findByText("This rider account is deactivated. Ask an admin to reactivate it."),
    ).toBeInTheDocument();
    expect(screen.getByRole("switch", { name: "On duty" })).toBeDisabled();
  });
});
