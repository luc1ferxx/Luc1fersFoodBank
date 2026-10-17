import { describe, expect, it } from "vitest";
import { DecodeError } from "./decode";
import { decodeNotification, decodeOrder } from "./dto";

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
