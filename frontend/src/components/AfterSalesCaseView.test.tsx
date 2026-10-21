import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import type { AfterSalesCase } from "../api/dto";
import { afterSalesCaseFixture, localTimestamp, refundFixture } from "../test/fakeBackend";
import { AfterSalesCaseView } from "./AfterSalesCaseView";

const HOUR = 60 * 60 * 1000;

const renderCase = (overrides: Partial<AfterSalesCase>) => {
  const handlers = {
    onAcceptOffer: vi.fn(),
    onDeclineOffer: vi.fn(),
    onEscalate: vi.fn((_note: string) => Promise.resolve(true)),
    onWithdraw: vi.fn(() => Promise.resolve(true)),
  };
  const user = userEvent.setup();
  render(
    <AfterSalesCaseView
      afterSalesCase={afterSalesCaseFixture({ id: 900, order_id: 7, ...overrides })}
      label="Order #7391823564100000007"
      pending={undefined}
      error=""
      {...handlers}
    />,
  );
  return { user, ...handlers };
};

/** The case's own buttons, without the history toggle every case has. */
const commandButtons = () =>
  screen
    .getAllByRole("button")
    .map((button) => button.textContent)
    .filter((name) => name !== "Refund history");

describe("AfterSalesCaseView", () => {
  it("waits for the restaurant with a countdown and can only be withdrawn", () => {
    renderCase({ deadline_at: localTimestamp(2 * HOUR + 30 * 60 * 1000) });

    expect(screen.getByText("Waiting for the restaurant")).toBeInTheDocument();
    expect(screen.getByText(/^Restaurant answers in 2h (29|30)m/)).toBeInTheDocument();
    expect(screen.getByText("Quality issue: The burger was cold.")).toBeInTheDocument();
    expect(commandButtons()).toEqual(["Withdraw request"]);
  });

  it("answers an offer before its deadline, and not after it", async () => {
    const { user, onAcceptOffer, onDeclineOffer } = renderCase({
      status: "COUNTER_OFFERED",
      offered_amount: 3,
      merchant_decision: "OFFERED",
      merchant_note: "One was fine",
      deadline_at: localTimestamp(47 * HOUR),
    });

    expect(commandButtons()).toEqual(["Accept $3.00", "Decline offer", "Withdraw request"]);
    expect(screen.getByText("Restaurant's note: One was fine")).toBeInTheDocument();
    expect(screen.getByText(/^Offer accepted automatically in 1d 2[23]h/)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Accept $3.00" }));
    await user.click(screen.getByRole("button", { name: "Decline offer" }));
    expect(onAcceptOffer).toHaveBeenCalledTimes(1);
    expect(onDeclineOffer).toHaveBeenCalledTimes(1);
  });

  it("disables the answers once the deadline passed", () => {
    renderCase({
      status: "COUNTER_OFFERED",
      offered_amount: 3,
      merchant_decision: "OFFERED",
      deadline_at: localTimestamp(-HOUR),
    });

    expect(screen.getByText(/^Deadline passed/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Accept $3.00" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Decline offer" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Withdraw request" })).toBeEnabled();
  });

  it("asks the platform to step in after a rejection, with an optional note", async () => {
    const { user, onEscalate } = renderCase({
      status: "REJECTED",
      merchant_decision: "REJECTED",
      merchant_note: "Looked fine when it left",
      deadline_at: localTimestamp(48 * HOUR),
    });

    expect(screen.getByText("Restaurant's reason: Looked fine when it left")).toBeInTheDocument();
    expect(commandButtons()).toEqual(["Ask the platform to step in", "Withdraw request"]);
    await user.click(screen.getByRole("button", { name: "Ask the platform to step in" }));
    const form = screen.getByRole("form", {
      name: "Ask the platform about Order #7391823564100000007",
    });
    await user.type(within(form).getByLabelText("Note for the platform (optional)"), "Photos");
    await user.click(within(form).getByRole("button", { name: "Send to the platform" }));

    expect(onEscalate).toHaveBeenCalledWith("Photos");
    expect(
      screen.queryByRole("form", { name: "Ask the platform about Order #7391823564100000007" }),
    ).toBeNull();
  });

  it("withdraws only after the confirmation", async () => {
    const { user, onWithdraw } = renderCase({
      status: "IN_ARBITRATION",
      deadline_at: localTimestamp(72 * HOUR),
    });

    expect(commandButtons()).toEqual(["Withdraw request"]);
    await user.click(screen.getByRole("button", { name: "Withdraw request" }));
    const dialog = screen.getByRole("dialog", { name: "Withdraw this refund request?" });
    await user.click(within(dialog).getByRole("button", { name: "Keep request" }));
    expect(onWithdraw).not.toHaveBeenCalled();

    await user.click(screen.getByRole("button", { name: "Withdraw request" }));
    await user.click(screen.getByRole("button", { name: "Withdraw" }));
    expect(onWithdraw).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole("dialog")).toBeNull();
  });

  it("shows the refund of an approved case and the reason a case closed, without buttons", () => {
    renderCase({
      status: "REFUNDING",
      approved_amount: 6.39,
      merchant_decision: "APPROVED",
      resolution: "MERCHANT_APPROVED",
      deadline_at: null,
      refund: refundFixture({ id: 901, case_id: 900, order_id: 7, status: "PROCESSING" }),
    });

    expect(screen.getByText("Refunding")).toBeInTheDocument();
    expect(screen.getByText("Approved by the restaurant")).toBeInTheDocument();
    expect(screen.getByText("Refund of $6.39: Processing")).toBeInTheDocument();
    expect(commandButtons()).toEqual([]);
  });

  it("names why a closed case closed", () => {
    renderCase({ status: "CLOSED", close_reason: "WITHDRAWN", deadline_at: null });

    expect(screen.getByText("Closed: Withdrawn")).toBeInTheDocument();
    expect(commandButtons()).toEqual([]);
  });
});
