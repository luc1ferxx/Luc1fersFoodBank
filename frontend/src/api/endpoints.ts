import { createIdempotencyKey, request } from "./client";
import { decodeArray } from "./decode";
import {
  type AfterSalesCase,
  type AfterSalesOverview,
  type AfterSalesStatus,
  type ArbitrateAfterSalesRequest,
  type Cart,
  type CreateMerchantRequest,
  type CreateRiderRequest,
  type Credentials,
  type CurrentUser,
  type DeadLetterReplay,
  type DeliveryStatus,
  type MenuItem,
  type MerchantAccount,
  type MerchantAfterSalesCase,
  type MerchantOrder,
  type OpenAfterSalesRequest,
  type Order,
  type OrderNotification,
  type OrderStatus,
  type OrderStatusChange,
  type PaymentDetails,
  type Refund,
  type RefundStatus,
  type Restaurant,
  type RiderAccount,
  type RiderDelivery,
  type RiderProfile,
  type SignupRequest,
  decodeAfterSalesCase,
  decodeAfterSalesOverview,
  decodeCart,
  decodeCurrentUser,
  decodeDeadLetterReplay,
  decodeMenuItem,
  decodeMerchantAccount,
  decodeMerchantAfterSalesCase,
  decodeMerchantOrder,
  decodeNotification,
  decodeOrder,
  decodeOrderStatusChange,
  decodeRefund,
  decodeRestaurant,
  decodeRiderAccount,
  decodeRiderDelivery,
  decodeRiderProfile,
} from "./dto";

export interface RequestControl {
  signal?: AbortSignal | undefined;
}

export interface ListControl extends RequestControl {
  limit?: number;
}

const jsonHeaders = { "Content-Type": "application/json" };

// ---- Session ----

export const login = async (
  { username, password }: Credentials,
  { signal }: RequestControl = {},
) => {
  await request("/login", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8" },
    body: new URLSearchParams({ username, password }).toString(),
    signal,
    fallbackMessage: "Fail to log in",
    reportUnauthorized: false,
  });
};

export const logout = async () => {
  await request("/logout", {
    method: "POST",
    fallbackMessage: "Fail to log out",
    reportUnauthorized: false,
  });
};

export const signup = async (data: SignupRequest) => {
  await request("/signup", {
    method: "POST",
    headers: jsonHeaders,
    body: JSON.stringify(data),
    fallbackMessage: "Fail to sign up",
  });
};

export const getCurrentUser = async ({ signal }: RequestControl = {}): Promise<CurrentUser> =>
  decodeCurrentUser(
    await request("/me", {
      signal,
      fallbackMessage: "Fail to load your session",
      reportUnauthorized: false,
    }),
    "me",
  );

// ---- Catalog ----

export const getRestaurants = async ({ signal }: RequestControl = {}): Promise<Restaurant[]> =>
  decodeArray(decodeRestaurant)(
    await request("/restaurants/menu", { signal, fallbackMessage: "Fail to get restaurants" }),
    "restaurants",
  );

export const getMenus = async (
  restaurantId: number,
  { signal }: RequestControl = {},
): Promise<MenuItem[]> =>
  decodeArray(decodeMenuItem)(
    await request(`/restaurant/${restaurantId}/menu`, {
      signal,
      fallbackMessage: "Fail to get menus",
    }),
    "menu",
  );

// ---- Cart ----

export const getCart = async ({ signal }: RequestControl = {}): Promise<Cart> =>
  decodeCart(
    await request("/cart", { signal, fallbackMessage: "Fail to get shopping cart data" }),
    "cart",
  );

/** Empties the signed-in customer's cart (204, idempotent). */
export const clearCart = async () => {
  await request("/cart", {
    method: "DELETE",
    fallbackMessage: "Fail to clear the shopping cart",
  });
};

/** Fails with 409 CART_RESTAURANT_CONFLICT when the cart holds another restaurant's items. */
export const addItemToCart = async (menuItemId: number) => {
  await request("/cart", {
    method: "POST",
    headers: jsonHeaders,
    body: JSON.stringify({ menu_id: menuItemId }),
    fallbackMessage: "Fail to add menu item to shopping cart",
  });
};

export const updateCartItem = async (orderItemId: number, quantity: number) => {
  await request(`/cart/items/${orderItemId}`, {
    method: "PUT",
    headers: jsonHeaders,
    body: JSON.stringify({ quantity }),
    fallbackMessage: "Fail to update shopping cart item",
  });
};

export const removeCartItem = async (orderItemId: number) => {
  await request(`/cart/items/${orderItemId}`, {
    method: "DELETE",
    fallbackMessage: "Fail to remove shopping cart item",
  });
};

// ---- Orders ----

// Callers pass the same key when retrying the same payment so the backend can
// replay the stored result instead of charging twice.
export const checkoutWithPayment = async (
  paymentDetails: PaymentDetails,
  idempotencyKey: string = createIdempotencyKey(),
): Promise<Order> =>
  decodeOrder(
    await request("/payments/checkout", {
      method: "POST",
      headers: { ...jsonHeaders, "Idempotency-Key": idempotencyKey },
      body: JSON.stringify(paymentDetails),
      fallbackMessage: "Fail to complete payment",
    }),
    "order",
  );

export const getOrders = async ({ limit = 50, signal }: ListControl = {}): Promise<Order[]> =>
  decodeArray(decodeOrder)(
    await request(`/orders?limit=${encodeURIComponent(limit)}`, {
      signal,
      fallbackMessage: "Fail to load your orders",
    }),
    "orders",
  );

export const cancelOrder = async (
  orderId: number,
  idempotencyKey: string = createIdempotencyKey(),
): Promise<Order> =>
  decodeOrder(
    await request(`/orders/${orderId}/cancel`, {
      method: "POST",
      headers: { "Idempotency-Key": idempotencyKey },
      fallbackMessage: "Fail to cancel the order",
    }),
    "order",
  );

/** The order's status changes, oldest first (the order's owner or an admin; others get 404). */
export const getOrderHistory = async (
  orderId: number,
  { signal }: RequestControl = {},
): Promise<OrderStatusChange[]> =>
  decodeArray(decodeOrderStatusChange)(
    await request(`/orders/${orderId}/history`, {
      signal,
      fallbackMessage: "Fail to load the order's status history",
    }),
    "history",
  );

export const getNotifications = async ({ limit = 50, signal }: ListControl = {}): Promise<
  OrderNotification[]
> =>
  decodeArray(decodeNotification)(
    await request(`/notifications?limit=${encodeURIComponent(limit)}`, {
      signal,
      fallbackMessage: "Fail to load notifications",
    }),
    "notifications",
  );

// ---- Merchant (ROLE_MERCHANT, scoped to the login's restaurant) ----
//
// The merchant commands take no Idempotency-Key: they are idempotent by state, so
// repeating one whose transition already happened returns the order unchanged, and a
// command the order's state no longer allows is 409 ORDER_STATUS_CONFLICT.

const merchantOrderPath = (orderId: number, command: string) =>
  `/merchant/orders/${orderId}/${command}`;

export const getMerchantOrders = async ({
  status,
  limit = 50,
  signal,
}: ListControl & { status?: OrderStatus | undefined } = {}): Promise<MerchantOrder[]> => {
  const query = new URLSearchParams({ limit: String(limit) });
  if (status) {
    query.set("status", status);
  }
  return decodeArray(decodeMerchantOrder)(
    await request(`/merchant/orders?${query.toString()}`, {
      signal,
      fallbackMessage: "Fail to load the restaurant's orders",
    }),
    "merchant_orders",
  );
};

export const acceptMerchantOrder = async (orderId: number): Promise<MerchantOrder> =>
  decodeMerchantOrder(
    await request(merchantOrderPath(orderId, "accept"), {
      method: "POST",
      fallbackMessage: "Fail to accept the order",
    }),
    "merchant_order",
  );

export const rejectMerchantOrder = async (
  orderId: number,
  reason: string,
): Promise<MerchantOrder> =>
  decodeMerchantOrder(
    await request(merchantOrderPath(orderId, "reject"), {
      method: "POST",
      headers: jsonHeaders,
      body: JSON.stringify({ reason }),
      fallbackMessage: "Fail to reject the order",
    }),
    "merchant_order",
  );

export const markMerchantOrderPreparing = async (orderId: number): Promise<MerchantOrder> =>
  decodeMerchantOrder(
    await request(merchantOrderPath(orderId, "preparing"), {
      method: "POST",
      fallbackMessage: "Fail to mark the order as preparing",
    }),
    "merchant_order",
  );

// ---- Rider (ROLE_RIDER, only the rider's own data) ----
//
// Like the merchant commands, picked-up and delivered are idempotent by state (a
// retry returns the delivery unchanged; a wrong state is 409) and take no key.

export const getRiderProfile = async ({ signal }: RequestControl = {}): Promise<RiderProfile> =>
  decodeRiderProfile(
    await request("/rider/me", { signal, fallbackMessage: "Fail to load your rider profile" }),
    "rider",
  );

export const updateRiderAvailability = async (onDuty: boolean): Promise<RiderProfile> =>
  decodeRiderProfile(
    await request("/rider/me/availability", {
      method: "PUT",
      headers: jsonHeaders,
      body: JSON.stringify({ on_duty: onDuty }),
      fallbackMessage: "Fail to change your availability",
    }),
    "rider",
  );

/** Without a status the backend returns the ASSIGNED and PICKED_UP deliveries. */
export const getRiderDeliveries = async ({
  status,
  signal,
}: RequestControl & { status?: DeliveryStatus | undefined } = {}): Promise<RiderDelivery[]> =>
  decodeArray(decodeRiderDelivery)(
    await request(
      status ? `/rider/deliveries?status=${encodeURIComponent(status)}` : "/rider/deliveries",
      { signal, fallbackMessage: "Fail to load your deliveries" },
    ),
    "deliveries",
  );

export const markDeliveryPickedUp = async (deliveryId: number): Promise<RiderDelivery> =>
  decodeRiderDelivery(
    await request(`/rider/deliveries/${deliveryId}/picked-up`, {
      method: "POST",
      fallbackMessage: "Fail to mark the delivery as picked up",
    }),
    "delivery",
  );

export const markDeliveryDelivered = async (deliveryId: number): Promise<RiderDelivery> =>
  decodeRiderDelivery(
    await request(`/rider/deliveries/${deliveryId}/delivered`, {
      method: "POST",
      fallbackMessage: "Fail to mark the delivery as delivered",
    }),
    "delivery",
  );

// ---- Admin (ROLE_ADMIN) ----

// Account provisioning: an email that already has a login is 409
// EMAIL_ALREADY_REGISTERED; an unknown restaurant for a merchant is 404.

export const getMerchantAccounts = async ({ signal }: RequestControl = {}): Promise<
  MerchantAccount[]
> =>
  decodeArray(decodeMerchantAccount)(
    await request("/admin/merchants", { signal, fallbackMessage: "Fail to load the merchants" }),
    "merchants",
  );

export const createMerchantAccount = async (
  body: CreateMerchantRequest,
): Promise<MerchantAccount> =>
  decodeMerchantAccount(
    await request("/admin/merchants", {
      method: "POST",
      headers: jsonHeaders,
      body: JSON.stringify(body),
      fallbackMessage: "Fail to create the merchant",
    }),
    "merchant",
  );

export const setMerchantAccountActive = async (
  merchantId: number,
  active: boolean,
): Promise<MerchantAccount> =>
  decodeMerchantAccount(
    await request(`/admin/merchants/${merchantId}`, {
      method: "PATCH",
      headers: jsonHeaders,
      body: JSON.stringify({ active }),
      fallbackMessage: "Fail to update the merchant",
    }),
    "merchant",
  );

export const getRiderAccounts = async ({ signal }: RequestControl = {}): Promise<RiderAccount[]> =>
  decodeArray(decodeRiderAccount)(
    await request("/admin/riders", { signal, fallbackMessage: "Fail to load the riders" }),
    "riders",
  );

export const createRiderAccount = async (body: CreateRiderRequest): Promise<RiderAccount> =>
  decodeRiderAccount(
    await request("/admin/riders", {
      method: "POST",
      headers: jsonHeaders,
      body: JSON.stringify(body),
      fallbackMessage: "Fail to create the rider",
    }),
    "rider",
  );

export const setRiderAccountActive = async (
  riderId: number,
  active: boolean,
): Promise<RiderAccount> =>
  decodeRiderAccount(
    await request(`/admin/riders/${riderId}`, {
      method: "PATCH",
      headers: jsonHeaders,
      body: JSON.stringify({ active }),
      fallbackMessage: "Fail to update the rider",
    }),
    "rider",
  );

export const updateOrderStatus = async (
  orderId: number,
  status: OrderStatus,
  idempotencyKey: string = createIdempotencyKey(),
): Promise<Order> =>
  decodeOrder(
    await request(`/orders/${orderId}/status`, {
      method: "PATCH",
      headers: { ...jsonHeaders, "Idempotency-Key": idempotencyKey },
      body: JSON.stringify({ status }),
      fallbackMessage: "Fail to update order status",
    }),
    "order",
  );

export const replayDeadLetter = async (deadLetterEventId: number): Promise<DeadLetterReplay> =>
  decodeDeadLetterReplay(
    await request(`/dead-letters/${deadLetterEventId}/replay`, {
      method: "POST",
      fallbackMessage: "Fail to replay the dead-letter event",
    }),
    "dead_letter",
  );

// ---- After-sales ----
//
// The customer's commands and an admin's arbitration take an Idempotency-Key: a retry
// of the same command with its key answers with the case the first one opened or
// changed, and the same key with another body is 409. A second request for an order
// (after withdrawing the first) needs a new key; the first one's key replays the
// withdrawn case. The merchant's answers and a refund retry are idempotent by state
// and take no key. A command the case's status no longer allows is 409
// AFTER_SALES_STATUS_CONFLICT; an answer after its step's deadline is 409
// AFTER_SALES_DEADLINE_PASSED.

/** The signed-in customer's cases (without history) and the orders open to a request. */
export const getAfterSalesOverview = async ({
  limit = 50,
  signal,
}: ListControl = {}): Promise<AfterSalesOverview> =>
  decodeAfterSalesOverview(
    await request(`/after-sales/cases?limit=${encodeURIComponent(limit)}`, {
      signal,
      fallbackMessage: "Fail to load your refund requests",
    }),
    "after_sales",
  );

/** One of the customer's cases with its history; anyone else's is 404. */
export const getAfterSalesCase = async (
  caseId: number,
  { signal }: RequestControl = {},
): Promise<AfterSalesCase> =>
  decodeAfterSalesCase(
    await request(`/after-sales/cases/${caseId}`, {
      signal,
      fallbackMessage: "Fail to load the refund request",
    }),
    "after_sales_case",
  );

/** 409 AFTER_SALES_NOT_ELIGIBLE when the order cannot get a request now. */
export const openAfterSalesCase = async (
  orderId: number,
  body: OpenAfterSalesRequest,
  idempotencyKey: string,
): Promise<AfterSalesCase> =>
  decodeAfterSalesCase(
    await request(`/orders/${orderId}/after-sales`, {
      method: "POST",
      headers: { ...jsonHeaders, "Idempotency-Key": idempotencyKey },
      body: JSON.stringify(body),
      fallbackMessage: "Fail to submit the refund request",
    }),
    "after_sales_case",
  );

const customerCaseCommand = async (
  caseId: number,
  command: string,
  idempotencyKey: string,
  fallbackMessage: string,
  body?: unknown,
): Promise<AfterSalesCase> =>
  decodeAfterSalesCase(
    await request(`/after-sales/cases/${caseId}/${command}`, {
      method: "POST",
      headers:
        body === undefined
          ? { "Idempotency-Key": idempotencyKey }
          : { ...jsonHeaders, "Idempotency-Key": idempotencyKey },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
      fallbackMessage,
    }),
    "after_sales_case",
  );

export const acceptAfterSalesOffer = (caseId: number, idempotencyKey: string) =>
  customerCaseCommand(caseId, "accept-offer", idempotencyKey, "Fail to accept the offer");

export const declineAfterSalesOffer = (caseId: number, idempotencyKey: string) =>
  customerCaseCommand(caseId, "decline-offer", idempotencyKey, "Fail to decline the offer");

/** Asks the platform to step in; `note` (optional, up to 1000 characters) goes into the history. */
export const escalateAfterSalesCase = (
  caseId: number,
  note: string | null,
  idempotencyKey: string,
) =>
  customerCaseCommand(
    caseId,
    "escalate",
    idempotencyKey,
    "Fail to ask the platform to step in",
    note ? { note } : undefined,
  );

export const withdrawAfterSalesCase = (caseId: number, idempotencyKey: string) =>
  customerCaseCommand(caseId, "withdraw", idempotencyKey, "Fail to withdraw the refund request");

// Merchant (ROLE_MERCHANT): the inbox of the login's restaurant. A deactivated
// account gets 403 on every request; another restaurant's case is 404.

const merchantAfterSalesPath = (caseId: number, command: string) =>
  `/merchant/after-sales/${caseId}/${command}`;

export const getMerchantAfterSalesCases = async ({
  status,
  limit = 50,
  signal,
}: ListControl & { status?: AfterSalesStatus | undefined } = {}): Promise<
  MerchantAfterSalesCase[]
> => {
  const query = new URLSearchParams({ limit: String(limit) });
  if (status) {
    query.set("status", status);
  }
  return decodeArray(decodeMerchantAfterSalesCase)(
    await request(`/merchant/after-sales?${query.toString()}`, {
      signal,
      fallbackMessage: "Fail to load the restaurant's refund requests",
    }),
    "merchant_after_sales",
  );
};

export const approveMerchantAfterSales = async (caseId: number): Promise<MerchantAfterSalesCase> =>
  decodeMerchantAfterSalesCase(
    await request(merchantAfterSalesPath(caseId, "approve"), {
      method: "POST",
      fallbackMessage: "Fail to approve the refund",
    }),
    "merchant_after_sales_case",
  );

/** `reason` is required (up to 500 characters) and shown to the customer. */
export const rejectMerchantAfterSales = async (
  caseId: number,
  reason: string,
): Promise<MerchantAfterSalesCase> =>
  decodeMerchantAfterSalesCase(
    await request(merchantAfterSalesPath(caseId, "reject"), {
      method: "POST",
      headers: jsonHeaders,
      body: JSON.stringify({ reason }),
      fallbackMessage: "Fail to reject the refund request",
    }),
    "merchant_after_sales_case",
  );

/** A partial refund: more than zero and less than the requested amount, in the case's currency. */
export const offerMerchantAfterSales = async (
  caseId: number,
  amount: number,
  note: string | null,
): Promise<MerchantAfterSalesCase> =>
  decodeMerchantAfterSalesCase(
    await request(merchantAfterSalesPath(caseId, "offer"), {
      method: "POST",
      headers: jsonHeaders,
      body: JSON.stringify(note ? { amount, note } : { amount }),
      fallbackMessage: "Fail to offer a partial refund",
    }),
    "merchant_after_sales_case",
  );

// Admin (ROLE_ADMIN): the arbitration queue and the refunds the executor gave up.

/** The cases in `status`; the backend defaults to the arbitration queue (IN_ARBITRATION). */
export const getAdminAfterSalesCases = async ({
  status,
  limit = 50,
  signal,
}: ListControl & { status?: AfterSalesStatus | undefined } = {}): Promise<AfterSalesCase[]> => {
  const query = new URLSearchParams({ limit: String(limit) });
  if (status) {
    query.set("status", status);
  }
  return decodeArray(decodeAfterSalesCase)(
    await request(`/admin/after-sales?${query.toString()}`, {
      signal,
      fallbackMessage: "Fail to load the refund requests",
    }),
    "after_sales_cases",
  );
};

/** Any case with its history; its refund carries `last_error`. */
export const getAdminAfterSalesCase = async (
  caseId: number,
  { signal }: RequestControl = {},
): Promise<AfterSalesCase> =>
  decodeAfterSalesCase(
    await request(`/admin/after-sales/${caseId}`, {
      signal,
      fallbackMessage: "Fail to load the refund request",
    }),
    "after_sales_case",
  );

export const arbitrateAfterSalesCase = async (
  caseId: number,
  body: ArbitrateAfterSalesRequest,
  idempotencyKey: string,
): Promise<AfterSalesCase> =>
  decodeAfterSalesCase(
    await request(`/admin/after-sales/${caseId}/arbitrate`, {
      method: "POST",
      headers: { ...jsonHeaders, "Idempotency-Key": idempotencyKey },
      body: JSON.stringify(body),
      fallbackMessage: "Fail to arbitrate the refund request",
    }),
    "after_sales_case",
  );

/** The refunds in `status`; the backend defaults to the ones given up (FAILED). */
export const getAdminRefunds = async ({
  status,
  limit = 50,
  signal,
}: ListControl & { status?: RefundStatus | undefined } = {}): Promise<Refund[]> => {
  const query = new URLSearchParams({ limit: String(limit) });
  if (status) {
    query.set("status", status);
  }
  return decodeArray(decodeRefund)(
    await request(`/admin/refunds?${query.toString()}`, {
      signal,
      fallbackMessage: "Fail to load the refunds",
    }),
    "refunds",
  );
};

/** Moves a FAILED refund back to PENDING; any other status is 409 REFUND_STATUS_CONFLICT. */
export const retryRefund = async (refundId: number): Promise<Refund> =>
  decodeRefund(
    await request(`/admin/refunds/${refundId}/retry`, {
      method: "POST",
      fallbackMessage: "Fail to retry the refund",
    }),
    "refund",
  );

// ---- Push signals (ADR 0003) ----

/**
 * The change-signal stream (`text/event-stream`, the session cookie, same origin). The
 * ServerSignals provider opens it with EventSource, not with request(); its events'
 * data is decoded with `decodePushSignal`.
 */
export const EVENT_STREAM_PATH = "/events/stream";
