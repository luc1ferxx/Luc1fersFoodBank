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

/** GET /orders, checkout, cancel, and status change responses (OrderDto). */
export interface Order {
  id: number;
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

/** GET /notifications (OrderNotificationDto). */
export interface OrderNotification {
  id: number;
  order_id: number;
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

/** GET /merchant/orders and the accept/reject/preparing responses (MerchantOrderDto). */
export interface MerchantOrder {
  order_id: number;
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

/** GET /rider/deliveries and the picked-up/delivered responses: the rider's own deliveries. */
export interface RiderDelivery {
  delivery_id: number;
  order_id: number;
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
