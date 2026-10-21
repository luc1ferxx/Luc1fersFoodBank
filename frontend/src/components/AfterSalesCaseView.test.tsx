import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import type { AfterSalesCase, Refund } from "../api/dto";
import { afterSalesCaseFixture, backendTimestamp, refundFixture } from "../test/fakeBackend";
import { AfterSalesCaseView, AutomaticRefund } from "./AfterSalesCaseView";

const HOUR = 60 * 60 * 1000;

const SUPPORT_WILL_CALL =
  "The refund did not go through. Our support team will contact you about it.";

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
    renderCase({ deadline_at: backendTimestamp(2 * HOUR + 30 * 60 * 1000) });

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
      deadline_at: backendTimestamp(47 * HOUR),
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
      deadline_at: backendTimestamp(-HOUR),
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
      deadline_at: backendTimestamp(48 * HOUR),
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
      deadline_at: backendTimestamp(72 * HOUR),
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
    expect(screen.queryByText(SUPPORT_WILL_CALL)).toBeNull();
    expect(commandButtons()).toEqual([]);
  });

  it("shows a failed refund and that support will contact the customer, without its error", () => {
    renderCase({
      status: "REFUNDING",
      approved_amount: 6.39,
      merchant_decision: "APPROVED",
      resolution: "MERCHANT_APPROVED",
      deadline_at: null,
      refund: refundFixture({
        id: 901,
        case_id: 900,
        order_id: 7,
        status: "FAILED",
        attempts: 5,
        last_error: "Gateway timeout",
        next_attempt_at: null,
      }),
    });

    expect(screen.getByText("Refund failed", { selector: ".status-badge" })).toHaveClass(
      "status-badge--FAILED",
    );
    expect(screen.queryByText("Refunding")).toBeNull();
    expect(screen.getByText("Refund of $6.39: Failed")).toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent(SUPPORT_WILL_CALL);
    expect(screen.queryByText(/Gateway timeout/)).toBeNull();
    expect(commandButtons()).toEqual([]);
  });

  it("names why a closed case closed", () => {
    renderCase({ status: "CLOSED", close_reason: "WITHDRAWN", deadline_at: null });

    expect(screen.getByText("Closed: Withdrawn")).toBeInTheDocument();
    expect(commandButtons()).toEqual([]);
  });
});

describe("AutomaticRefund", () => {
  const cancelledOrderRefund = (refund: Partial<Refund>) =>
    afterSalesCaseFixture({
      id: 900,
      order_id: 7,
      kind: "ORDER_CANCELLED",
      status: "REFUNDING",
      reason_code: null,
      description: null,
      requested_amount: 9,
      approved_amount: 9,
      resolution: "ORDER_CANCELLED",
      deadline_at: null,
      refund: refundFixture({ id: 901, case_id: 900, order_id: 7, amount: 9, ...refund }),
    });

  it("shows the refund of a cancelled order on its way", () => {
    render(<AutomaticRefund afterSalesCase={cancelledOrderRefund({ status: "PROCESSING" })} />);

    expect(screen.getByText("Automatic refund $9.00: Refunding")).toBeInTheDocument();
    expect(screen.queryByRole("status")).toBeNull();
  });

  it("shows that the refund failed and that support will contact the customer", () => {
    render(
      <AutomaticRefund
        afterSalesCase={cancelledOrderRefund({ status: "FAILED", last_error: "Card closed" })}
      />,
    );

    expect(screen.getByText("Automatic refund $9.00: Refund failed")).toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent(SUPPORT_WILL_CALL);
    expect(screen.queryByText(/Card closed/)).toBeNull();
  });
});
