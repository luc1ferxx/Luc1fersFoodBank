import type { CurrencyCode } from "./currency";
import { type Decoder, decodeString, fieldsOf } from "./decode";

/*
 * Request and response shapes of the backend API (snake_case, from the global Jackson
 * naming strategy), each response type next to its decoder. Field names follow the
 * backend records in backend/src/main/java/com/laioffer/onlineorder (model/*Dto and
 * fulfilment/**). Java fields that can be null are `T | null` here.
 *
 * Every amount is a JSON number with a sibling `currency` (ISO 4217): "price" +
 * "currency", "total_price" + "currency". A restaurant prices in one currency, so a
 * cart and an order have one too. Amounts are only formatted (`formatMoney`) and
 * multiplied by quantities in integer hundredths (`lineTotal`), never summed here.
 */

export const ORDER_STATUSES = [
  "PLACED",
  "PAID",
  "ACCEPTED",
  "PREPARING",
  "COMPLETED",
  "CANCELLED",
] as const;
export type OrderStatus = (typeof ORDER_STATUSES)[number];

export const DELIVERY_STATUSES = ["ASSIGNED", "PICKED_UP", "DELIVERED", "CANCELLED"] as const;
export type DeliveryStatus = (typeof DELIVERY_STATUSES)[number];

/** Machine-readable `code` values of backend error bodies that the UI reacts to. */
export const ERROR_CODES = {
  /** POST /cart: the cart already holds items of another restaurant (409). */
  cartRestaurantConflict: "CART_RESTAURANT_CONFLICT",
  /** POST /admin/merchants and /admin/riders: the email already has a login (409). */
  emailAlreadyRegistered: "EMAIL_ALREADY_REGISTERED",
  /**
   * POST /orders/{id}/after-sales: the order cannot get a request now (not completed,
   * outside its window, a request already open, or none left) (409).
   */
  afterSalesNotEligible: "AFTER_SALES_NOT_ELIGIBLE",
  /** An after-sales command the case's status no longer allows (409). */
  afterSalesStatusConflict: "AFTER_SALES_STATUS_CONFLICT",
  /** A customer's or merchant's answer after the deadline of the case's step (409). */
  afterSalesDeadlinePassed: "AFTER_SALES_DEADLINE_PASSED",
  /** POST /admin/refunds/{id}/retry of a refund that is not FAILED (409). */
  refundStatusConflict: "REFUND_STATUS_CONFLICT",
  /** The rate limiter refused the request (429, with a Retry-After header). */
  rateLimited: "RATE_LIMITED",
} as const;

/** The OrderStatus spelled by `value` (e.g. a <select> value), or null. */
export const parseOrderStatus = (value: string): OrderStatus | null =>
  ORDER_STATUSES.find((status) => status === value) ?? null;

// ---- Session ----

export interface Credentials {
  username: string;
  password: string;
}

export interface SignupRequest {
  email: string;
  password: string;
  first_name: string;
  last_name: string;
}

/** Spring Security authorities that pick the UI's workspaces. */
export const ROLES = {
  user: "ROLE_USER",
  admin: "ROLE_ADMIN",
  merchant: "ROLE_MERCHANT",
  rider: "ROLE_RIDER",
} as const;

/** The restaurant a merchant login is bound to (GET /me `merchant`). */
export interface MerchantBinding {
  merchant_id: number;
  restaurant_id: number;
  restaurant_name: string | null;
  active: boolean;
}

export const decodeMerchantBinding: Decoder<MerchantBinding> = (value, path) => {
  const f = fieldsOf(value, path);
  return {
    merchant_id: f.number("merchant_id"),
    restaurant_id: f.number("restaurant_id"),
    restaurant_name: f.optionalString("restaurant_name"),
    active: f.boolean("active"),
  };
};

/** The rider record of a rider login (GET /me `rider`). */
export interface RiderBinding {
  rider_id: number;
  name: string;
  on_duty: boolean;
  active: boolean;
}

export const decodeRiderBinding: Decoder<RiderBinding> = (value, path) => {
  const f = fieldsOf(value, path);
  return {
    rider_id: f.number("rider_id"),
    name: f.string("name"),
    on_duty: f.boolean("on_duty"),
    active: f.boolean("active"),
  };
};

/**
 * GET /me (CurrentUserDto). `roles`, `merchant`, and `rider` decode as [] and null
 * when a backend without them answers, which leaves the user on the ordering page.
 */
export interface CurrentUser {
  id: number;
  email: string;
  first_name: string | null;
  last_name: string | null;
  roles: string[];
  merchant: MerchantBinding | null;
  rider: RiderBinding | null;
}

export const decodeCurrentUser: Decoder<CurrentUser> = (value, path) => {
  const f = fieldsOf(value, path);
  return {
    id: f.number("id"),
    email: f.string("email"),
    first_name: f.optionalString("first_name"),
    last_name: f.optionalString("last_name"),
    roles: f.arrayOrEmpty("roles", decodeString),
    merchant: f.optionalObject("merchant", decodeMerchantBinding),
    rider: f.optionalObject("rider", decodeRiderBinding),
  };
};

// ---- Catalog ----

/** GET /restaurants/menu (RestaurantDto; its menu_items are not used by the UI). */
export interface Restaurant {
  id: number;
  name: string;
  address: string | null;
  phone: string | null;
  image_url: string | null;
  /** The currency the restaurant's menu is priced in. */
  currency: CurrencyCode;
}

export const decodeRestaurant: Decoder<Restaurant> = (value, path) => {
  const f = fieldsOf(value, path);
  return {
    id: f.number("id"),
    name: f.string("name"),
    address: f.optionalString("address"),
    phone: f.optionalString("phone"),
    image_url: f.optionalString("image_url"),
    currency: f.currency("currency"),
  };
};

/** GET /restaurant/{id}/menu (CatalogMenuItemDto). */
export interface MenuItem {
  id: number;
  restaurant_id: number;
  name: string;
  description: string | null;
  price: number;
  currency: CurrencyCode;
  image_url: string | null;
}

export const decodeMenuItem: Decoder<MenuItem> = (value, path) => {
  const f = fieldsOf(value, path);
  return {
    id: f.number("id"),
    restaurant_id: f.number("restaurant_id"),
    name: f.string("name"),
    description: f.optionalString("description"),
    price: f.number("price"),
    currency: f.currency("currency"),
    image_url: f.optionalString("image_url"),
  };
};

// ---- Cart ----

/** One cart line (OrderItemDto). */
export interface CartItem {
  order_item_id: number;
  menu_item_id: number;
  restaurant_id: number | null;
  price: number;
  currency: CurrencyCode;
  quantity: number;
  menu_item_name: string;
  menu_item_description: string | null;
  menu_item_image_url: string | null;
}

export const decodeCartItem: Decoder<CartItem> = (value, path) => {
  const f = fieldsOf(value, path);
  return {
    order_item_id: f.number("order_item_id"),
    menu_item_id: f.number("menu_item_id"),
    restaurant_id: f.optionalNumber("restaurant_id"),
    price: f.number("price"),
    currency: f.currency("currency"),
    quantity: f.number("quantity"),
    menu_item_name: f.string("menu_item_name"),
    menu_item_description: f.optionalString("menu_item_description"),
    menu_item_image_url: f.optionalString("menu_item_image_url"),
  };
};

/** GET /cart (CartDto). An empty cart keeps the currency it last had. */
export interface Cart {
  total_price: number;
  currency: CurrencyCode;
  order_items: CartItem[];
}

export const decodeCart: Decoder<Cart> = (value, path) => {
  const f = fieldsOf(value, path);
  return {
    total_price: f.optionalNumber("total_price") ?? 0,
    currency: f.currency("currency"),
    order_items: f.arrayOrEmpty("order_items", decodeCartItem),
  };
};

// ---- Orders ----

/** POST /payments/checkout body (PaymentCheckoutBody). */
export interface PaymentDetails {
  cardholder_name: string;
  card_number: string;
  expiry: string;
  cvv: string;
}

/** An item of a placed order (OrderHistoryItemDto). */
export interface OrderItem {
  id: number;
  menu_item_id: number;
  restaurant_id: number | null;
  price: number;
  currency: CurrencyCode;
  quantity: number;
  menu_item_name: string;
  menu_item_description: string | null;
  menu_item_image_url: string | null;
}

export const decodeOrderItem: Decoder<OrderItem> = (value, path) => {
  const f = fieldsOf(value, path);
  return {
    id: f.number("id"),
    menu_item_id: f.number("menu_item_id"),
    restaurant_id: f.optionalNumber("restaurant_id"),
    price: f.number("price"),
    currency: f.currency("currency"),
    quantity: f.number("quantity"),
    menu_item_name: f.string("menu_item_name"),
    menu_item_description: f.optionalString("menu_item_description"),
    menu_item_image_url: f.optionalString("menu_item_image_url"),
  };
};

/**
 * GET /orders, checkout, cancel, and status change responses (OrderDto). `id` is the
 * internal key the order endpoints take; `order_no` is the public order number shown to
 * people (a 64-bit Snowflake id, sent as a string because it does not fit a JS number).
 */
export interface Order {
  id: number;
  order_no: string | null;
  total_price: number;
  currency: CurrencyCode;
  status: OrderStatus;
  created_at: string | null;
  items: OrderItem[];
  restaurant_id: number | null;
  cancel_reason: string | null;
}

export const decodeOrder: Decoder<Order> = (value, path) => {
  const f = fieldsOf(value, path);
  return {
    id: f.number("id"),
    order_no: f.optionalString("order_no"),
    total_price: f.number("total_price"),
    currency: f.currency("currency"),
    status: f.oneOf("status", ORDER_STATUSES),
    created_at: f.optionalString("created_at"),
    items: f.arrayOrEmpty("items", decodeOrderItem),
    restaurant_id: f.optionalNumber("restaurant_id"),
    cancel_reason: f.optionalString("cancel_reason"),
  };
};

export const ACTOR_TYPES = ["CUSTOMER", "ADMIN", "MERCHANT", "RIDER", "SYSTEM"] as const;
/** Who moved an order: the customer, an admin, the restaurant, a rider, or a timeout/consumer. */
export type ActorType = (typeof ACTOR_TYPES)[number];

/** GET /orders/{id}/history, oldest first. `from_status` is null for the order's creation. */
export interface OrderStatusChange {
  from_status: OrderStatus | null;
  to_status: OrderStatus;
  actor_type: ActorType;
  actor_id: number | null;
  reason: string | null;
  occurred_at: string;
}

export const decodeOrderStatusChange: Decoder<OrderStatusChange> = (value, path) => {
  const f = fieldsOf(value, path);
  return {
    from_status: f.optionalOneOf("from_status", ORDER_STATUSES),
    to_status: f.oneOf("to_status", ORDER_STATUSES),
    actor_type: f.oneOf("actor_type", ACTOR_TYPES),
    actor_id: f.optionalNumber("actor_id"),
    reason: f.optionalString("reason"),
    occurred_at: f.string("occurred_at"),
  };
};

/** GET /notifications (OrderNotificationDto); `order_no` as in Order. */
export interface OrderNotification {
  id: number;
  order_id: number;
  order_no: string | null;
  event_type: string;
  title: string;
  message: string;
  created_at: string | null;
}

export const decodeNotification: Decoder<OrderNotification> = (value, path) => {
  const f = fieldsOf(value, path);
  return {
    id: f.number("id"),
    order_id: f.number("order_id"),
    order_no: f.optionalString("order_no"),
    event_type: f.string("event_type"),
    title: f.string("title"),
    message: f.string("message"),
    created_at: f.optionalString("created_at"),
  };
};

// ---- Merchant (ROLE_MERCHANT) ----

/** An item of a merchant order (MerchantOrderDto.Item). */
export interface MerchantOrderItem {
  menu_item_id: number;
  menu_item_name: string;
  price: number;
  currency: CurrencyCode;
  quantity: number;
}

export const decodeMerchantOrderItem: Decoder<MerchantOrderItem> = (value, path) => {
  const f = fieldsOf(value, path);
  return {
    menu_item_id: f.number("menu_item_id"),
    menu_item_name: f.string("menu_item_name"),
    price: f.number("price"),
    currency: f.currency("currency"),
    quantity: f.number("quantity"),
  };
};

/**
 * GET /merchant/orders and the accept/reject/preparing responses (MerchantOrderDto).
 * `order_id` is the key the merchant endpoints take; `order_no` as in Order (null for an
 * order the backend sends no number for).
 */
export interface MerchantOrder {
  order_id: number;
  order_no: string | null;
  restaurant_id: number;
  customer_id: number;
  status: OrderStatus;
  total_price: number;
  currency: CurrencyCode;
  items: MerchantOrderItem[];
  cancel_reason: string | null;
  paid_at: string | null;
  updated_at: string | null;
}

export const decodeMerchantOrder: Decoder<MerchantOrder> = (value, path) => {
  const f = fieldsOf(value, path);
  return {
    order_id: f.number("order_id"),
    order_no: f.optionalString("order_no"),
    restaurant_id: f.number("restaurant_id"),
    customer_id: f.number("customer_id"),
    status: f.oneOf("status", ORDER_STATUSES),
    total_price: f.number("total_price"),
    currency: f.currency("currency"),
    items: f.arrayOrEmpty("items", decodeMerchantOrderItem),
    cancel_reason: f.optionalString("cancel_reason"),
    paid_at: f.optionalString("paid_at"),
    updated_at: f.optionalString("updated_at"),
  };
};

// ---- Rider (ROLE_RIDER) ----

/** GET /rider/me and PUT /rider/me/availability. */
export interface RiderProfile {
  rider_id: number;
  name: string;
  phone: string | null;
  active: boolean;
  on_duty: boolean;
  active_deliveries: number;
}

export const decodeRiderProfile: Decoder<RiderProfile> = (value, path) => {
  const f = fieldsOf(value, path);
  return {
    rider_id: f.number("rider_id"),
    name: f.string("name"),
    phone: f.optionalString("phone"),
    active: f.boolean("active"),
    on_duty: f.boolean("on_duty"),
    active_deliveries: f.optionalNumber("active_deliveries") ?? 0,
  };
};

/**
 * GET /rider/deliveries and the picked-up/delivered responses: the rider's own deliveries.
 * `order_no` as in MerchantOrder.
 */
export interface RiderDelivery {
  delivery_id: number;
  order_id: number;
  order_no: string | null;
  restaurant_id: number;
  restaurant_name: string | null;
  status: DeliveryStatus;
  assigned_at: string | null;
  picked_up_at: string | null;
  delivered_at: string | null;
}

export const decodeRiderDelivery: Decoder<RiderDelivery> = (value, path) => {
  const f = fieldsOf(value, path);
  return {
    delivery_id: f.number("delivery_id"),
    order_id: f.number("order_id"),
    order_no: f.optionalString("order_no"),
    restaurant_id: f.number("restaurant_id"),
    restaurant_name: f.optionalString("restaurant_name"),
    status: f.oneOf("status", DELIVERY_STATUSES),
    assigned_at: f.optionalString("assigned_at"),
    picked_up_at: f.optionalString("picked_up_at"),
    delivered_at: f.optionalString("delivered_at"),
  };
};

// ---- Admin (ROLE_ADMIN) ----

/** GET /admin/merchants, and the POST / PATCH responses: a merchant login and its restaurant. */
export interface MerchantAccount {
  merchant_id: number;
  customer_id: number;
  email: string;
  first_name: string | null;
  last_name: string | null;
  restaurant_id: number;
  restaurant_name: string | null;
  active: boolean;
  created_at: string | null;
}

export const decodeMerchantAccount: Decoder<MerchantAccount> = (value, path) => {
  const f = fieldsOf(value, path);
  return {
    merchant_id: f.number("merchant_id"),
    customer_id: f.number("customer_id"),
    email: f.string("email"),
    first_name: f.optionalString("first_name"),
    last_name: f.optionalString("last_name"),
    restaurant_id: f.number("restaurant_id"),
    restaurant_name: f.optionalString("restaurant_name"),
    active: f.boolean("active"),
    created_at: f.optionalString("created_at"),
  };
};

/** POST /admin/merchants body. */
export interface CreateMerchantRequest {
  email: string;
  password: string;
  first_name: string;
  last_name: string;
  restaurant_id: number;
}

/**
 * GET /admin/riders, and the POST / PATCH responses. `customer_id` and `email` are
 * nullable because riders seeded before provisioning (the demo riders) have no login.
 */
export interface RiderAccount {
  rider_id: number;
  customer_id: number | null;
  email: string | null;
  first_name: string | null;
  last_name: string | null;
  name: string;
  phone: string | null;
  active: boolean;
  on_duty: boolean;
  active_deliveries: number;
  created_at: string | null;
}

export const decodeRiderAccount: Decoder<RiderAccount> = (value, path) => {
  const f = fieldsOf(value, path);
  return {
    rider_id: f.number("rider_id"),
    customer_id: f.optionalNumber("customer_id"),
    email: f.optionalString("email"),
    first_name: f.optionalString("first_name"),
    last_name: f.optionalString("last_name"),
    name: f.string("name"),
    phone: f.optionalString("phone"),
    active: f.boolean("active"),
    on_duty: f.boolean("on_duty"),
    active_deliveries: f.optionalNumber("active_deliveries") ?? 0,
    created_at: f.optionalString("created_at"),
  };
};

/** POST /admin/riders body. */
export interface CreateRiderRequest {
  email: string;
  password: string;
  first_name: string;
  last_name: string;
  phone: string;
}

/** POST /dead-letters/{id}/replay (DeadLetterReplayDto). */
export interface DeadLetterReplay {
  id: number;
  source_topic: string | null;
  dead_letter_topic: string | null;
  replay_status: string;
  replay_attempts: number | null;
  last_replay_error: string | null;
}

export const decodeDeadLetterReplay: Decoder<DeadLetterReplay> = (value, path) => {
  const f = fieldsOf(value, path);
  return {
    id: f.number("id"),
    source_topic: f.optionalString("source_topic"),
    dead_letter_topic: f.optionalString("dead_letter_topic"),
    replay_status: f.string("replay_status"),
    replay_attempts: f.optionalNumber("replay_attempts"),
    last_replay_error: f.optionalString("last_replay_error"),
  };
};

// ---- After-sales (ADR 0002 sections 11 and 12) ----

/**
 * The status of an after-sales case. The four open ones wait for someone's answer
 * until `deadline_at` and can be withdrawn; REFUNDING waits for the refund; REFUNDED
 * and CLOSED are final.
 */
export const AFTER_SALES_STATUSES = [
  "PENDING_MERCHANT",
  "COUNTER_OFFERED",
  "REJECTED",
  "IN_ARBITRATION",
  "REFUNDING",
  "REFUNDED",
  "CLOSED",
] as const;
export type AfterSalesStatus = (typeof AFTER_SALES_STATUSES)[number];

/** The statuses that wait for an answer until a deadline and that the customer can withdraw. */
export const AFTER_SALES_OPEN_STATUSES: ReadonlySet<AfterSalesStatus> = new Set<AfterSalesStatus>([
  "PENDING_MERCHANT",
  "COUNTER_OFFERED",
  "REJECTED",
  "IN_ARBITRATION",
]);

/** The statuses a case never leaves. */
export const AFTER_SALES_FINAL_STATUSES: ReadonlySet<AfterSalesStatus> = new Set<AfterSalesStatus>([
  "REFUNDED",
  "CLOSED",
]);

/** The AfterSalesStatus spelled by `value` (e.g. a <select> value), or null. */
export const parseAfterSalesStatus = (value: string): AfterSalesStatus | null =>
  AFTER_SALES_STATUSES.find((status) => status === value) ?? null;

export const AFTER_SALES_REASONS = [
  "MISSING_ITEM",
  "WRONG_ITEM",
  "QUALITY_ISSUE",
  "DAMAGED_PACKAGING",
  "FOOD_SAFETY",
  "LATE_DELIVERY",
  "OTHER",
] as const;
export type AfterSalesReason = (typeof AFTER_SALES_REASONS)[number];

/** A customer's request, or the automatic refund of a cancelled paid order. */
export const AFTER_SALES_KINDS = ["CUSTOMER_REQUEST", "ORDER_CANCELLED"] as const;
export type AfterSalesKind = (typeof AFTER_SALES_KINDS)[number];

export const AFTER_SALES_MERCHANT_DECISIONS = ["APPROVED", "REJECTED", "OFFERED"] as const;
export type AfterSalesMerchantDecision = (typeof AFTER_SALES_MERCHANT_DECISIONS)[number];

/** How the approved amount of a case in REFUNDING or REFUNDED was set. */
export const AFTER_SALES_RESOLUTIONS = [
  "MERCHANT_APPROVED",
  "MERCHANT_TIMEOUT",
  "OFFER_ACCEPTED",
  "OFFER_TIMEOUT",
  "ARBITRATION_FULL",
  "ARBITRATION_PARTIAL",
  "ARBITRATION_TIMEOUT",
  "ORDER_CANCELLED",
] as const;
export type AfterSalesResolution = (typeof AFTER_SALES_RESOLUTIONS)[number];

/** Why a CLOSED case was closed without a refund. Only a WITHDRAWN one allows a second request. */
export const AFTER_SALES_CLOSE_REASONS = [
  "WITHDRAWN",
  "CUSTOMER_TIMEOUT",
  "ARBITRATION_REJECTED",
] as const;
export type AfterSalesCloseReason = (typeof AFTER_SALES_CLOSE_REASONS)[number];

/** The steps of a case's history: its opening (OPEN, ORDER_CANCELLED) or the trigger that moved it. */
export const AFTER_SALES_ACTIONS = [
  "OPEN",
  "ORDER_CANCELLED",
  "MERCHANT_APPROVE",
  "MERCHANT_REJECT",
  "MERCHANT_OFFER",
  "MERCHANT_TIMEOUT",
  "CUSTOMER_ACCEPT_OFFER",
  "CUSTOMER_DECLINE_OFFER",
  "OFFER_TIMEOUT",
  "CUSTOMER_ESCALATE",
  "CUSTOMER_TIMEOUT",
  "CUSTOMER_WITHDRAW",
  "ADMIN_APPROVE_FULL",
  "ADMIN_APPROVE_PARTIAL",
  "ADMIN_REJECT",
  "ARBITRATION_TIMEOUT",
  "REFUND_SUCCEEDED",
] as const;
export type AfterSalesAction = (typeof AFTER_SALES_ACTIONS)[number];

/** Who took an after-sales step (riders take none). */
export const AFTER_SALES_ACTOR_TYPES = ["CUSTOMER", "MERCHANT", "ADMIN", "SYSTEM"] as const;
export type AfterSalesActorType = (typeof AFTER_SALES_ACTOR_TYPES)[number];

/** A refund is FAILED once the executor gave up; an admin can retry it. SUCCEEDED is final. */
export const REFUND_STATUSES = ["PENDING", "PROCESSING", "SUCCEEDED", "FAILED"] as const;
export type RefundStatus = (typeof REFUND_STATUSES)[number];

/** The RefundStatus spelled by `value`, or null. */
export const parseRefundStatus = (value: string): RefundStatus | null =>
  REFUND_STATUSES.find((status) => status === value) ?? null;

/** A line of a case (AfterSalesLineDto): an order line and the quantity asked for. */
export interface AfterSalesLine {
  /** The order line, `items[].id` of the Order. */
  order_item_id: number;
  menu_item_name: string;
  quantity: number;
  unit_price: number;
  /** unit_price x quantity. */
  amount: number;
  currency: CurrencyCode;
}

export const decodeAfterSalesLine: Decoder<AfterSalesLine> = (value, path) => {
  const f = fieldsOf(value, path);
  return {
    order_item_id: f.number("order_item_id"),
    menu_item_name: f.string("menu_item_name"),
    quantity: f.number("quantity"),
    unit_price: f.number("unit_price"),
    amount: f.number("amount"),
    currency: f.currency("currency"),
  };
};

/** A step of a case (AfterSalesHistoryDto), oldest first; `from_status` is null for the opening. */
export interface AfterSalesHistoryEntry {
  action: AfterSalesAction;
  from_status: AfterSalesStatus | null;
  to_status: AfterSalesStatus;
  actor_type: AfterSalesActorType;
  /** The amount the step set (requested, offered, approved or refunded). */
  amount: number | null;
  currency: CurrencyCode;
  /** The merchant's reason, the customer's escalation note or the admin's note. */
  note: string | null;
  occurred_at: string;
}

export const decodeAfterSalesHistoryEntry: Decoder<AfterSalesHistoryEntry> = (value, path) => {
  const f = fieldsOf(value, path);
  return {
    action: f.oneOf("action", AFTER_SALES_ACTIONS),
    from_status: f.optionalOneOf("from_status", AFTER_SALES_STATUSES),
    to_status: f.oneOf("to_status", AFTER_SALES_STATUSES),
    actor_type: f.oneOf("actor_type", AFTER_SALES_ACTOR_TYPES),
    amount: f.optionalNumber("amount"),
    currency: f.currency("currency"),
    note: f.optionalString("note"),
    occurred_at: f.string("occurred_at"),
  };
};

/**
 * The refund of a case that reached REFUNDING (RefundDto). `last_error` is only in the
 * admin views; a customer's answer never carries it.
 */
export interface Refund {
  id: number;
  case_id: number;
  order_id: number;
  order_no: string | null;
  amount: number;
  currency: CurrencyCode;
  status: RefundStatus;
  attempts: number;
  last_error: string | null;
  provider_reference: string | null;
  /** When a PENDING refund is tried next. */
  next_attempt_at: string | null;
  created_at: string;
  succeeded_at: string | null;
}

export const decodeRefund: Decoder<Refund> = (value, path) => {
  const f = fieldsOf(value, path);
  return {
    id: f.number("id"),
    case_id: f.number("case_id"),
    order_id: f.number("order_id"),
    order_no: f.optionalString("order_no"),
    amount: f.number("amount"),
    currency: f.currency("currency"),
    status: f.oneOf("status", REFUND_STATUSES),
    attempts: f.number("attempts"),
    last_error: f.optionalString("last_error"),
    provider_reference: f.optionalString("provider_reference"),
    next_attempt_at: f.optionalString("next_attempt_at"),
    created_at: f.string("created_at"),
    succeeded_at: f.optionalString("succeeded_at"),
  };
};

/**
 * An after-sales case (AfterSalesCaseDto). `id` is the case id the case endpoints
 * take; every amount is in `currency`, the order's. `history` is only sent by the
 * endpoints about one case and decodes as [] in lists.
 */
export interface AfterSalesCase {
  id: number;
  order_id: number;
  order_no: string | null;
  restaurant_id: number | null;
  kind: AfterSalesKind;
  /** 1, or 2 for the customer's request after withdrawing the first. */
  attempt_no: number;
  status: AfterSalesStatus;
  /** Null for an ORDER_CANCELLED case. */
  reason_code: AfterSalesReason | null;
  description: string | null;
  requested_amount: number;
  offered_amount: number | null;
  /** The amount to refund, from REFUNDING on. */
  approved_amount: number | null;
  refunded_amount: number | null;
  currency: CurrencyCode;
  lines: AfterSalesLine[];
  merchant_decision: AfterSalesMerchantDecision | null;
  merchant_note: string | null;
  resolution: AfterSalesResolution | null;
  close_reason: AfterSalesCloseReason | null;
  admin_note: string | null;
  /** Until when an open status waits for its answer; null from REFUNDING on. */
  deadline_at: string | null;
  refund: Refund | null;
  history: AfterSalesHistoryEntry[];
  created_at: string;
  updated_at: string;
}

export const decodeAfterSalesCase: Decoder<AfterSalesCase> = (value, path) => {
  const f = fieldsOf(value, path);
  return {
    id: f.number("id"),
    order_id: f.number("order_id"),
    order_no: f.optionalString("order_no"),
    restaurant_id: f.optionalNumber("restaurant_id"),
    kind: f.oneOf("kind", AFTER_SALES_KINDS),
    attempt_no: f.number("attempt_no"),
    status: f.oneOf("status", AFTER_SALES_STATUSES),
    reason_code: f.optionalOneOf("reason_code", AFTER_SALES_REASONS),
    description: f.optionalString("description"),
    requested_amount: f.number("requested_amount"),
    offered_amount: f.optionalNumber("offered_amount"),
    approved_amount: f.optionalNumber("approved_amount"),
    refunded_amount: f.optionalNumber("refunded_amount"),
    currency: f.currency("currency"),
    lines: f.arrayOrEmpty("lines", decodeAfterSalesLine),
    merchant_decision: f.optionalOneOf("merchant_decision", AFTER_SALES_MERCHANT_DECISIONS),
    merchant_note: f.optionalString("merchant_note"),
    resolution: f.optionalOneOf("resolution", AFTER_SALES_RESOLUTIONS),
    close_reason: f.optionalOneOf("close_reason", AFTER_SALES_CLOSE_REASONS),
    admin_note: f.optionalString("admin_note"),
    deadline_at: f.optionalString("deadline_at"),
    refund: f.optionalObject("refund", decodeRefund),
    history: f.arrayOrEmpty("history", decodeAfterSalesHistoryEntry),
    created_at: f.string("created_at"),
    updated_at: f.string("updated_at"),
  };
};

/** An order of the customer that can get an after-sales request now (EligibleOrderDto). */
export interface EligibleOrder {
  order_id: number;
  order_no: string | null;
  /** 1, or 2 (the last one) after the customer withdrew the first request. */
  attempt_no: number;
  /** The end of the order's request window. */
  request_deadline: string;
}

export const decodeEligibleOrder: Decoder<EligibleOrder> = (value, path) => {
  const f = fieldsOf(value, path);
  return {
    order_id: f.number("order_id"),
    order_no: f.optionalString("order_no"),
    attempt_no: f.number("attempt_no"),
    request_deadline: f.string("request_deadline"),
  };
};

/** GET /after-sales/cases: the customer's cases, newest first, and the orders open to a request. */
export interface AfterSalesOverview {
  cases: AfterSalesCase[];
  eligible_orders: EligibleOrder[];
}

export const decodeAfterSalesOverview: Decoder<AfterSalesOverview> = (value, path) => {
  const f = fieldsOf(value, path);
  return {
    cases: f.arrayOrEmpty("cases", decodeAfterSalesCase),
    eligible_orders: f.arrayOrEmpty("eligible_orders", decodeEligibleOrder),
  };
};

/** WHOLE_ORDER asks for every line in full and sends no lines; ITEMS sends the lines. */
export const AFTER_SALES_SCOPES = ["WHOLE_ORDER", "ITEMS"] as const;
export type AfterSalesScope = (typeof AFTER_SALES_SCOPES)[number];

/** POST /orders/{orderId}/after-sales body (OpenAfterSalesBody). */
export interface OpenAfterSalesRequest {
  scope: AfterSalesScope;
  lines: { order_item_id: number; quantity: number }[];
  reason_code: AfterSalesReason;
  description: string;
}

/** A case as the merchant's after-sales inbox shows it (MerchantAfterSalesCaseDto). */
export interface MerchantAfterSalesCase {
  /** The id the merchant's commands take. */
  case_id: number;
  order_id: number;
  order_no: string | null;
  status: AfterSalesStatus;
  reason_code: AfterSalesReason;
  description: string;
  lines: AfterSalesLine[];
  requested_amount: number;
  offered_amount: number | null;
  approved_amount: number | null;
  currency: CurrencyCode;
  merchant_decision: AfterSalesMerchantDecision | null;
  merchant_note: string | null;
  /** The merchant's deadline while PENDING_MERCHANT; null from REFUNDING on. */
  deadline_at: string | null;
  opened_at: string;
  updated_at: string;
}

export const decodeMerchantAfterSalesCase: Decoder<MerchantAfterSalesCase> = (value, path) => {
  const f = fieldsOf(value, path);
  return {
    case_id: f.number("case_id"),
    order_id: f.number("order_id"),
    order_no: f.optionalString("order_no"),
    status: f.oneOf("status", AFTER_SALES_STATUSES),
    reason_code: f.oneOf("reason_code", AFTER_SALES_REASONS),
    description: f.string("description"),
    lines: f.arrayOrEmpty("lines", decodeAfterSalesLine),
    requested_amount: f.number("requested_amount"),
    offered_amount: f.optionalNumber("offered_amount"),
    approved_amount: f.optionalNumber("approved_amount"),
    currency: f.currency("currency"),
    merchant_decision: f.optionalOneOf("merchant_decision", AFTER_SALES_MERCHANT_DECISIONS),
    merchant_note: f.optionalString("merchant_note"),
    deadline_at: f.optionalString("deadline_at"),
    opened_at: f.string("opened_at"),
    updated_at: f.string("updated_at"),
  };
};

/** An admin's decision on an escalated case: the requested amount, part of it, or none. */
export const ARBITRATION_DECISIONS = ["FULL", "PARTIAL", "REJECT"] as const;
export type ArbitrationDecision = (typeof ARBITRATION_DECISIONS)[number];

/** POST /admin/after-sales/{caseId}/arbitrate body; `amount` only for PARTIAL. */
export interface ArbitrateAfterSalesRequest {
  decision: ArbitrationDecision;
  amount?: number;
  note: string;
}
