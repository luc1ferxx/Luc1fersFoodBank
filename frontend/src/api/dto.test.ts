import { describe, expect, it } from "vitest";
import { EUR, USD } from "../test/fixtures";
import { DecodeError } from "./decode";
import {
  decodeAfterSalesCase,
  decodeAfterSalesOverview,
  decodeMerchantAfterSalesCase,
  decodeNotification,
  decodeOrder,
  decodeRefund,
} from "./dto";

const order = { id: 42, total_price: 9, status: "PAID", items: [] };

describe("order numbers", () => {
  it("keeps every digit of the order number, which the backend sends as a string", () => {
    // 2^63 - 1: as a JS number it would come back as 9223372036854775808.
    expect(decodeOrder({ ...order, order_no: "9223372036854775807" }, "order")).toMatchObject({
      id: 42,
      order_no: "9223372036854775807",
    });
    expect(
      decodeNotification(
        {
          id: 1,
          order_id: 42,
          order_no: "7391823564100000042",
          event_type: "order.paid",
          title: "Order paid",
          message: "Order #7391823564100000042 was paid successfully with 1 item(s).",
        },
        "notification",
      ).order_no,
    ).toBe("7391823564100000042");
  });

  it("decodes a missing order number as null", () => {
    expect(decodeOrder(order, "order").order_no).toBeNull();
  });

  it("rejects an order number sent as a JSON number, whose digits may already be lost", () => {
    const body: unknown = JSON.parse(
      '{"id": 42, "total_price": 9, "status": "PAID", "items": [], "order_no": 7391823564100000042}',
    );
    expect(() => decodeOrder(body, "order")).toThrow(DecodeError);
  });
});

describe("after-sales decoders", () => {
  const caseJson = {
    id: 900,
    order_id: 101,
    order_no: "7391823564100000101",
    restaurant_id: 1,
    kind: "CUSTOMER_REQUEST",
    attempt_no: 1,
    status: "COUNTER_OFFERED",
    reason_code: "QUALITY_ISSUE",
    description: "Cold",
    requested_amount: 12.78,
    offered_amount: 5,
    currency: "EUR",
    lines: [
      {
        order_item_id: 5,
        menu_item_name: "Whopper",
        quantity: 2,
        unit_price: 6.39,
        amount: 12.78,
        currency: "EUR",
      },
    ],
    merchant_decision: "OFFERED",
    deadline_at: "2026-10-22T12:00:00",
    created_at: "2026-10-20T12:00:00",
    updated_at: "2026-10-20T13:00:00",
  };

  it("decodes a case list entry, whose absent history and nulls the backend omits", () => {
    const decoded = decodeAfterSalesCase(caseJson, "case");
    expect(decoded).toMatchObject({
      id: 900,
      order_no: "7391823564100000101",
      status: "COUNTER_OFFERED",
      currency: "EUR",
      offered_amount: 5,
      approved_amount: null,
      refund: null,
      resolution: null,
      close_reason: null,
      history: [],
    });
    expect(decoded.lines[0]).toEqual({ ...caseJson.lines[0], currency: EUR });
  });

  it("decodes the history and the refund of a case's details", () => {
    const decoded = decodeAfterSalesCase(
      {
        ...caseJson,
        status: "REFUNDING",
        approved_amount: 5,
        resolution: "OFFER_ACCEPTED",
        refund: {
          id: 901,
          case_id: 900,
          order_id: 101,
          order_no: "7391823564100000101",
          amount: 5,
          currency: "EUR",
          status: "PENDING",
          attempts: 0,
          created_at: "2026-10-20T14:00:00",
        },
        history: [
          {
            action: "OPEN",
            to_status: "PENDING_MERCHANT",
            actor_type: "CUSTOMER",
            amount: 12.78,
            currency: "EUR",
            occurred_at: "2026-10-20T12:00:00",
          },
          {
            action: "CUSTOMER_ACCEPT_OFFER",
            from_status: "COUNTER_OFFERED",
            to_status: "REFUNDING",
            actor_type: "CUSTOMER",
            amount: 5,
            currency: "EUR",
            occurred_at: "2026-10-20T14:00:00",
          },
        ],
      },
      "case",
    );
    expect(decoded.refund).toMatchObject({ status: "PENDING", last_error: null, currency: EUR });
    expect(decoded.history.map((entry) => [entry.action, entry.from_status])).toEqual([
      ["OPEN", null],
      ["CUSTOMER_ACCEPT_OFFER", "COUNTER_OFFERED"],
    ]);
  });

  it("reads a missing currency as USD, like every other amount", () => {
    expect(decodeAfterSalesCase({ ...caseJson, currency: undefined }, "case").currency).toBe(USD);
    expect(
      decodeMerchantAfterSalesCase(
        {
          case_id: 900,
          order_id: 101,
          status: "PENDING_MERCHANT",
          reason_code: "OTHER",
          description: "x",
          requested_amount: 1,
          opened_at: "2026-10-20T12:00:00",
          updated_at: "2026-10-20T12:00:00",
        },
        "merchant_case",
      ).currency,
    ).toBe(USD);
  });

  it("rejects statuses, kinds and actions it does not know, naming the field", () => {
    expect(() => decodeAfterSalesCase({ ...caseJson, status: "ESCALATED" }, "case")).toThrow(
      "case.status should be one of PENDING_MERCHANT, COUNTER_OFFERED, REJECTED, IN_ARBITRATION, REFUNDING, REFUNDED, CLOSED",
    );
    expect(() => decodeAfterSalesCase({ ...caseJson, kind: "GOODWILL" }, "case")).toThrow(
      DecodeError,
    );
    expect(() =>
      decodeAfterSalesCase(
        {
          ...caseJson,
          history: [
            {
              action: "ADMIN_RETRY",
              to_status: "REFUNDING",
              actor_type: "ADMIN",
              currency: "EUR",
              occurred_at: "2026-10-20T12:00:00",
            },
          ],
        },
        "case",
      ),
    ).toThrow("case.history[0].action should be one of");
    expect(() =>
      decodeRefund(
        {
          id: 1,
          case_id: 2,
          order_id: 3,
          amount: 1,
          status: "CANCELLED",
          attempts: 0,
          created_at: "2026-10-20T12:00:00",
        },
        "refund",
      ),
    ).toThrow("refund.status should be one of PENDING, PROCESSING, SUCCEEDED, FAILED");
  });

  it("rejects an order number or amount of the wrong type", () => {
    expect(() => decodeAfterSalesCase({ ...caseJson, order_no: 7391823564100001 }, "case")).toThrow(
      DecodeError,
    );
    expect(() => decodeAfterSalesCase({ ...caseJson, requested_amount: "12.78" }, "case")).toThrow(
      "case.requested_amount should be a number",
    );
  });

  it("decodes the overview with its eligible orders", () => {
    expect(
      decodeAfterSalesOverview(
        {
          cases: [caseJson],
          eligible_orders: [
            {
              order_id: 102,
              order_no: "7391823564100000102",
              attempt_no: 2,
              request_deadline: "2026-10-21T12:00:00",
            },
          ],
        },
        "after_sales",
      ),
    ).toMatchObject({
      cases: [{ id: 900 }],
      eligible_orders: [{ order_id: 102, order_no: "7391823564100000102", attempt_no: 2 }],
    });
    expect(() => decodeAfterSalesOverview({ cases: {} }, "after_sales")).toThrow(
      "after_sales.cases should be an array",
    );
  });
});
