import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "../api/client";
import type { OpenAfterSalesRequest } from "../api/dto";
import * as api from "../api/endpoints";
import { parseTimestamp } from "../lib/format";
import {
  USD,
  afterSalesCaseFixture,
  backendTimestamp,
  customerUser,
  demoUser,
  installFakeBackend,
  merchantUser,
  orderFixture,
  refundFixture,
  settleAfterSalesRefund,
} from "./fakeBackend";

// The fake's after-sales routes follow the backend's contract (ADR 0002 section 11),
// so the UI tests built on them exercise the real rules: these pin the ones the UI
// depends on.

const completedOrder = orderFixture({
  id: 101,
  status: "COMPLETED",
  total_price: 12.78,
  items: [
    {
      id: 5,
      menu_item_id: 10,
      restaurant_id: 1,
      price: 6.39,
      currency: USD,
      quantity: 2,
      menu_item_name: "Whopper",
      menu_item_description: null,
      menu_item_image_url: null,
    },
  ],
});

const oneWhopper: OpenAfterSalesRequest = {
  scope: "ITEMS",
  lines: [{ order_item_id: 5, quantity: 1 }],
  reason_code: "QUALITY_ISSUE",
  description: "Cold",
};

const rejection = (promise: Promise<unknown>) =>
  promise.then(
    () => {
      throw new Error("Expected the request to fail");
    },
    (error: unknown) => {
      if (!(error instanceof ApiError)) {
        throw error;
      }
      return { status: error.status, code: error.code, message: error.message };
    },
  );

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("fake after-sales backend", () => {
  it("replays a request's key with the case it opened, and refuses the key for another request", async () => {
    const backend = installFakeBackend({ user: customerUser });
    backend.state.orders = [completedOrder];

    const opened = await api.openAfterSalesCase(101, oneWhopper, "open-1");
    expect(opened).toMatchObject({
      status: "PENDING_MERCHANT",
      attempt_no: 1,
      requested_amount: 6.39,
      history: [{ action: "OPEN", actor_type: "CUSTOMER" }],
    });
    await expect(api.openAfterSalesCase(101, oneWhopper, "open-1")).resolves.toMatchObject({
      id: opened.id,
    });
    expect(
      await rejection(
        api.openAfterSalesCase(101, { ...oneWhopper, scope: "WHOLE_ORDER", lines: [] }, "open-1"),
      ),
    ).toEqual({
      status: 409,
      code: "CONFLICT",
      message: "Idempotency key was already used with a different request",
    });
    expect(await rejection(api.openAfterSalesCase(101, oneWhopper, "open-2"))).toEqual({
      status: 409,
      code: "AFTER_SALES_NOT_ELIGIBLE",
      message: "An after-sales case is already open for this order",
    });
  });

  it("allows one more request after a withdrawal, with a new key only", async () => {
    const backend = installFakeBackend({ user: customerUser });
    backend.state.orders = [completedOrder];
    const first = await api.openAfterSalesCase(101, oneWhopper, "open-1");
    await api.withdrawAfterSalesCase(first.id, "withdraw-1");

    expect((await api.getAfterSalesOverview()).eligible_orders).toEqual([
      expect.objectContaining({ order_id: 101, attempt_no: 2 }),
    ]);
    // The first request's key replays the withdrawn case.
    await expect(api.openAfterSalesCase(101, oneWhopper, "open-1")).resolves.toMatchObject({
      id: first.id,
      status: "CLOSED",
      close_reason: "WITHDRAWN",
    });
    const second = await api.openAfterSalesCase(101, oneWhopper, "open-2");
    expect(second).toMatchObject({ attempt_no: 2, status: "PENDING_MERCHANT" });
    await api.withdrawAfterSalesCase(second.id, "withdraw-2");

    expect((await api.getAfterSalesOverview()).eligible_orders).toEqual([]);
    expect(await rejection(api.openAfterSalesCase(101, oneWhopper, "open-3"))).toMatchObject({
      code: "AFTER_SALES_NOT_ELIGIBLE",
      message: "This order cannot be submitted for after-sales again",
    });
  });

  it("allows another request only after a withdrawal before a rejection", async () => {
    const backend = installFakeBackend({ user: customerUser });
    backend.state.orders = [101, 102, 103, 104, 105].map((id) =>
      orderFixture({ id, status: "COMPLETED", total_price: 12.78, items: completedOrder.items }),
    );
    const openAndWithdraw = async (
      orderId: number,
      answer: (caseId: number) => Promise<unknown>,
    ) => {
      const opened = await api.openAfterSalesCase(orderId, oneWhopper, `open-${orderId}`);
      await answer(opened.id);
      backend.state.user = customerUser;
      const withdrawn = await api.withdrawAfterSalesCase(opened.id, `withdraw-${orderId}`);
      return withdrawn.history.at(-1)?.from_status;
    };
    const merchant = async (run: () => Promise<unknown>) => {
      backend.state.user = merchantUser;
      await run();
    };

    // Waiting for the merchant, and on a counter-offer: the order can be asked again.
    expect(await openAndWithdraw(101, () => Promise.resolve())).toBe("PENDING_MERCHANT");
    expect(
      await openAndWithdraw(102, (caseId) =>
        merchant(() => api.offerMerchantAfterSales(caseId, 3, null)),
      ),
    ).toBe("COUNTER_OFFERED");
    // After the merchant's rejection, a declined offer, or from arbitration: no other request.
    expect(
      await openAndWithdraw(103, (caseId) =>
        merchant(() => api.rejectMerchantAfterSales(caseId, "Looked fine")),
      ),
    ).toBe("REJECTED");
    expect(
      await openAndWithdraw(104, async (caseId) => {
        await merchant(() => api.offerMerchantAfterSales(caseId, 3, null));
        backend.state.user = customerUser;
        await api.declineAfterSalesOffer(caseId, `decline-${caseId}`);
      }),
    ).toBe("REJECTED");
    expect(
      await openAndWithdraw(105, async (caseId) => {
        await merchant(() => api.rejectMerchantAfterSales(caseId, "Looked fine"));
        backend.state.user = customerUser;
        await api.escalateAfterSalesCase(caseId, null, `escalate-${caseId}`);
      }),
    ).toBe("IN_ARBITRATION");

    expect((await api.getAfterSalesOverview()).eligible_orders).toEqual([
      expect.objectContaining({ order_id: 101, attempt_no: 2 }),
      expect.objectContaining({ order_id: 102, attempt_no: 2 }),
    ]);
    for (const orderId of [103, 104, 105]) {
      expect(
        await rejection(api.openAfterSalesCase(orderId, oneWhopper, `again-${orderId}`)),
      ).toEqual({
        status: 409,
        code: "AFTER_SALES_NOT_ELIGIBLE",
        message: "This order cannot be submitted for after-sales again",
      });
    }
    for (const orderId of [101, 102]) {
      await expect(
        api.openAfterSalesCase(orderId, oneWhopper, `again-${orderId}`),
      ).resolves.toMatchObject({ attempt_no: 2, status: "PENDING_MERCHANT" });
    }
  });

  it("walks a request through the merchant, the customer and arbitration", async () => {
    const backend = installFakeBackend({ user: customerUser });
    backend.state.orders = [completedOrder];
    const opened = await api.openAfterSalesCase(101, oneWhopper, "open-1");

    backend.state.user = merchantUser;
    expect(await rejection(api.offerMerchantAfterSales(opened.id, 6.39, null))).toMatchObject({
      status: 400,
      message: "The amount must be more than 0 and less than the requested 6.39 USD",
    });
    await expect(api.offerMerchantAfterSales(opened.id, 3, "Half")).resolves.toMatchObject({
      status: "COUNTER_OFFERED",
      offered_amount: 3,
    });
    // The same answer again is unchanged; another one is refused.
    await expect(api.offerMerchantAfterSales(opened.id, 3, "Half")).resolves.toMatchObject({
      status: "COUNTER_OFFERED",
    });
    expect(await rejection(api.approveMerchantAfterSales(opened.id))).toEqual({
      status: 409,
      code: "AFTER_SALES_STATUS_CONFLICT",
      message: "After-sales case is COUNTER_OFFERED; only PENDING_MERCHANT cases can be approved",
    });

    backend.state.user = customerUser;
    await api.declineAfterSalesOffer(opened.id, "decline-1");
    const escalated = await api.escalateAfterSalesCase(opened.id, "Please check", "escalate-1");
    expect(escalated.status).toBe("IN_ARBITRATION");
    expect(escalated.history.at(-1)).toMatchObject({
      action: "CUSTOMER_ESCALATE",
      note: "Please check",
    });

    backend.state.user = demoUser;
    const decided = await api.arbitrateAfterSalesCase(
      opened.id,
      { decision: "PARTIAL", amount: 4, note: "Partly cold" },
      "arbitrate-1",
    );
    expect(decided).toMatchObject({
      status: "REFUNDING",
      approved_amount: 4,
      resolution: "ARBITRATION_PARTIAL",
      admin_note: "Partly cold",
      refund: { status: "PENDING", amount: 4 },
    });
    // A retry with the key replays the decision instead of a 409 for the moved case.
    await expect(
      api.arbitrateAfterSalesCase(
        opened.id,
        { decision: "PARTIAL", amount: 4, note: "Partly cold" },
        "arbitrate-1",
      ),
    ).resolves.toMatchObject({ status: "REFUNDING" });
    expect(
      await rejection(
        api.arbitrateAfterSalesCase(opened.id, { decision: "REJECT", note: "No" }, "arbitrate-2"),
      ),
    ).toMatchObject({
      code: "AFTER_SALES_STATUS_CONFLICT",
      message: "After-sales case is REFUNDING; only IN_ARBITRATION cases can be arbitrated",
    });
  });

  it("refuses a late answer and keeps the refund's last error from the customer", async () => {
    const backend = installFakeBackend({ user: customerUser });
    backend.state.orders = [completedOrder];
    backend.state.afterSalesCases = [
      afterSalesCaseFixture({
        id: 900,
        order_id: 101,
        status: "COUNTER_OFFERED",
        offered_amount: 3,
        merchant_decision: "OFFERED",
        deadline_at: "2026-01-01T00:00:00",
      }),
      afterSalesCaseFixture({
        id: 902,
        order_id: 101,
        attempt_no: 1,
        status: "REFUNDING",
        approved_amount: 6.39,
        deadline_at: null,
        refund: refundFixture({
          id: 903,
          case_id: 902,
          order_id: 101,
          status: "FAILED",
          last_error: "Gateway timeout",
        }),
      }),
    ];

    expect(await rejection(api.acceptAfterSalesOffer(900, "accept-1"))).toEqual({
      status: 409,
      code: "AFTER_SALES_DEADLINE_PASSED",
      message: "The deadline of this after-sales step passed at 2026-01-01T00:00:00 UTC",
    });
    expect((await api.getAfterSalesCase(902)).refund?.last_error).toBeNull();

    backend.state.user = demoUser;
    expect((await api.getAdminAfterSalesCase(902)).refund?.last_error).toBe("Gateway timeout");
    expect(await api.getAdminRefunds()).toEqual([expect.objectContaining({ id: 903 })]);
    await expect(api.retryRefund(903)).resolves.toMatchObject({ status: "PENDING" });
    await expect(api.retryRefund(903)).resolves.toMatchObject({ status: "PENDING" });
    settleAfterSalesRefund(backend.state, 902);
    expect(await rejection(api.retryRefund(903))).toEqual({
      status: 409,
      code: "REFUND_STATUS_CONFLICT",
      message: "Refund is SUCCEEDED; only FAILED refunds can be retried",
    });
  });

  it("checks the roles: merchant only for an active account, admin only for admins", async () => {
    const backend = installFakeBackend({ user: customerUser });
    backend.state.afterSalesCases = [afterSalesCaseFixture({ id: 900, order_id: 101 })];

    expect(await rejection(api.getMerchantAfterSalesCases())).toMatchObject({ status: 403 });
    expect(await rejection(api.getAdminAfterSalesCases())).toMatchObject({ status: 403 });
    // Not the customer's order: 404, like anyone else's case.
    expect(await rejection(api.getAfterSalesCase(900))).toMatchObject({ status: 404 });

    backend.state.user = merchantUser;
    await expect(api.getMerchantAfterSalesCases({ status: "PENDING_MERCHANT" })).resolves.toEqual([
      expect.objectContaining({ case_id: 900, deadline_at: expect.any(String) }),
    ]);
    backend.state.user = {
      ...merchantUser,
      merchant: { merchant_id: 3, restaurant_id: 1, restaurant_name: null, active: false },
    };
    expect(await rejection(api.approveMerchantAfterSales(900))).toMatchObject({ status: 403 });

    backend.state.user = null;
    expect(await rejection(api.getAfterSalesOverview())).toMatchObject({ status: 401 });
  });

  it("writes times the way the backend does: UTC wall-clock time without an offset", () => {
    const hour = 60 * 60 * 1000;
    const written = backendTimestamp(hour);

    expect(written).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}$/);
    expect(Math.abs(parseTimestamp(written) - (Date.now() + hour))).toBeLessThan(2000);
    // Not the test zone's wall-clock time (New York, behind UTC).
    expect(Math.abs(Date.parse(written) - (Date.now() + hour))).toBeGreaterThan(hour);
  });

  it("opens the automatic refund when a paid order is cancelled", async () => {
    const backend = installFakeBackend({ user: customerUser });
    backend.state.orders = [{ ...completedOrder, status: "PAID" }];

    await api.cancelOrder(101, "cancel-1");

    expect((await api.getAfterSalesOverview()).cases).toEqual([
      expect.objectContaining({
        kind: "ORDER_CANCELLED",
        status: "REFUNDING",
        approved_amount: 12.78,
        refund: expect.objectContaining({ status: "PENDING", amount: 12.78 }),
      }),
    ]);
  });
});
