import { fieldsOf } from "../api/decode";
import {
  type ActorType,
  type MerchantOrder,
  type OrderStatus,
  ROLES,
  type RiderDelivery,
} from "../api/dto";
import type { FakeState } from "./fakeBackend";
import { USD } from "./fixtures";
import { type FakeHandler, type FakeRoute, apiError, idParam, json, requestJson } from "./fakeHttp";

/** What the fake's route groups share with the core fake backend. */
export interface FakeContext {
  state: FakeState;
  /** 401 without a session, like `anyRequest().authenticated()`. */
  requireSession: (handler: FakeHandler) => FakeHandler;
  /** 401 without a session, 403 without `role`, like the backend's security config. */
  requireRole: (role: string, handler: FakeHandler) => FakeHandler;
  recordStatusChange: (
    orderId: number,
    fromStatus: OrderStatus | null,
    toStatus: OrderStatus,
    actorType: ActorType,
    reason?: string | null,
  ) => void;
  /** Whether a login (seeded or provisioned) already uses this email. */
  emailRegistered: (email: string) => boolean;
}

/** A paid order in the merchant inbox, with defaults for what a test does not care about. */
export const merchantOrderFixture = (
  overrides: Partial<MerchantOrder> & Pick<MerchantOrder, "order_id">,
): MerchantOrder => ({
  restaurant_id: 1,
  customer_id: 1,
  status: "PAID",
  total_price: 12.78,
  currency: USD,
  items: [{ menu_item_id: 10, menu_item_name: "Whopper", price: 6.39, currency: USD, quantity: 2 }],
  cancel_reason: null,
  paid_at: "2026-10-16T12:00:00",
  updated_at: "2026-10-16T12:00:00",
  ...overrides,
});

/** A delivery of the signed-in rider. */
export const deliveryFixture = (
  overrides: Partial<RiderDelivery> & Pick<RiderDelivery, "delivery_id" | "order_id">,
): RiderDelivery => ({
  restaurant_id: 1,
  restaurant_name: "Burger Barn",
  status: "ASSIGNED",
  assigned_at: "2026-10-16T12:10:00",
  picked_up_at: null,
  delivered_at: null,
  ...overrides,
});

const NOW = "2026-10-16T12:30:00";

const forbidden = () => apiError(403, "Access denied", { code: "FORBIDDEN" });

/**
 * /merchant/** (ROLE_MERCHANT): the inbox of the merchant's restaurant in
 * `state.merchantOrders`. Commands are idempotent by state like the backend's: a
 * repeat returns the order unchanged, a wrong state is 409 ORDER_STATUS_CONFLICT.
 * Like the backend, every request re-checks the account: a login without an active
 * `state.user.merchant` gets 403 (replace `state.user` to deactivate mid-session).
 */
export function merchantRoutes({ state, requireRole, recordStatusChange }: FakeContext) {
  const asActiveMerchant = (handler: FakeHandler): FakeHandler =>
    requireRole(ROLES.merchant, (request, match) =>
      state.user?.merchant?.active === true ? handler(request, match) : forbidden(),
    );

  const decide = (
    orderId: number,
    required: OrderStatus,
    target: OrderStatus,
    cancelReason: string | null,
  ) => {
    const order = state.merchantOrders.find((item) => item.order_id === orderId);
    if (!order) {
      return apiError(404, "Order not found", { code: "NOT_FOUND" });
    }
    const repeated =
      order.status === target &&
      (target !== "CANCELLED" || order.cancel_reason?.startsWith("MERCHANT_REJECTED") === true);
    if (repeated) {
      return json(order);
    }
    if (order.status !== required) {
      return apiError(409, `Invalid order status transition: ${order.status} -> ${target}`, {
        code: "ORDER_STATUS_CONFLICT",
      });
    }
    recordStatusChange(orderId, order.status, target, "MERCHANT", cancelReason);
    order.status = target;
    order.cancel_reason = cancelReason;
    order.updated_at = NOW;
    return json({ ...order });
  };

  const routes: FakeRoute[] = [
    [
      "GET",
      /^\/merchant\/orders$/,
      asActiveMerchant((request) => {
        const status = request.query.get("status");
        const limit = Number(request.query.get("limit") ?? "50");
        const orders = status
          ? state.merchantOrders.filter((order) => order.status === status)
          : state.merchantOrders;
        return json(orders.slice(0, limit));
      }),
    ],
    [
      "POST",
      /^\/merchant\/orders\/(\d+)\/accept$/,
      asActiveMerchant((_request, match) => decide(idParam(match), "PAID", "ACCEPTED", null)),
    ],
    [
      "POST",
      /^\/merchant\/orders\/(\d+)\/reject$/,
      asActiveMerchant((request, match) => {
        const reason = fieldsOf(requestJson(request), "body").optionalString("reason")?.trim();
        if (!reason) {
          return apiError(400, "Request validation failed", { code: "VALIDATION_FAILED" });
        }
        return decide(idParam(match), "PAID", "CANCELLED", `MERCHANT_REJECTED: ${reason}`);
      }),
    ],
    [
      "POST",
      /^\/merchant\/orders\/(\d+)\/preparing$/,
      asActiveMerchant((_request, match) => decide(idParam(match), "ACCEPTED", "PREPARING", null)),
    ],
  ];
  return routes;
}

/**
 * /rider/** (ROLE_RIDER): `state.riderProfile` and the rider's own deliveries in
 * `state.deliveries`; any other id is 404. Picked-up and delivered are idempotent
 * by state; a wrong state is 409 DELIVERY_STATUS_CONFLICT. Like the backend, every
 * request re-checks the rider: no profile, or `active: false`, is 403 on all of them.
 */
export function riderRoutes({ state, requireRole }: FakeContext) {
  const withProfile = (handler: FakeHandler): FakeHandler =>
    requireRole(ROLES.rider, (request, match) =>
      state.riderProfile?.active === true ? handler(request, match) : forbidden(),
    );

  const move = (
    deliveryId: number,
    required: RiderDelivery["status"],
    target: RiderDelivery["status"],
  ) => {
    const delivery = state.deliveries.find((item) => item.delivery_id === deliveryId);
    if (!delivery) {
      return apiError(404, "Delivery not found", { code: "NOT_FOUND" });
    }
    if (delivery.status === target) {
      return json(delivery);
    }
    if (delivery.status !== required) {
      return apiError(409, `Invalid delivery status transition: ${delivery.status} -> ${target}`, {
        code: "DELIVERY_STATUS_CONFLICT",
      });
    }
    delivery.status = target;
    if (target === "PICKED_UP") {
      delivery.picked_up_at = NOW;
      // delivery.picked_up reaches the order module, which keeps the first pickup.
      if (!state.orderPickups.has(delivery.order_id)) {
        state.orderPickups.set(delivery.order_id, NOW);
      }
    } else if (target === "DELIVERED") {
      delivery.delivered_at = NOW;
      if (state.riderProfile) {
        state.riderProfile.active_deliveries = Math.max(
          0,
          state.riderProfile.active_deliveries - 1,
        );
      }
    }
    return json({ ...delivery });
  };

  const routes: FakeRoute[] = [
    ["GET", /^\/rider\/me$/, withProfile(() => json(state.riderProfile))],
    [
      "PUT",
      /^\/rider\/me\/availability$/,
      withProfile((request) => {
        const onDuty = fieldsOf(requestJson(request), "body").boolean("on_duty");
        if (state.riderProfile) {
          state.riderProfile.on_duty = onDuty;
        }
        return json(state.riderProfile);
      }),
    ],
    [
      "GET",
      /^\/rider\/deliveries$/,
      withProfile((request) => {
        const status = request.query.get("status");
        return json(
          state.deliveries.filter((delivery) =>
            status
              ? delivery.status === status
              : delivery.status === "ASSIGNED" || delivery.status === "PICKED_UP",
          ),
        );
      }),
    ],
    [
      "POST",
      /^\/rider\/deliveries\/(\d+)\/picked-up$/,
      withProfile((_request, match) => move(idParam(match), "ASSIGNED", "PICKED_UP")),
    ],
    [
      "POST",
      /^\/rider\/deliveries\/(\d+)\/delivered$/,
      withProfile((_request, match) => move(idParam(match), "PICKED_UP", "DELIVERED")),
    ],
  ];
  return routes;
}
