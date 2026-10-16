import { type Decoder, fieldsOf } from "./decode";

/*
 * Request and response shapes of the backend API (snake_case, from the global Jackson
 * naming strategy), each response type next to its decoder. Field names follow the
 * backend records in backend/src/main/java/com/laioffer/onlineorder (model/*Dto and
 * fulfilment/**). Java fields that can be null are `T | null` here.
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

/** GET /me (CurrentUserDto). */
export interface CurrentUser {
  id: number;
  email: string;
  first_name: string | null;
  last_name: string | null;
}

export const decodeCurrentUser: Decoder<CurrentUser> = (value, path) => {
  const f = fieldsOf(value, path);
  return {
    id: f.number("id"),
    email: f.string("email"),
    first_name: f.optionalString("first_name"),
    last_name: f.optionalString("last_name"),
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
}

export const decodeRestaurant: Decoder<Restaurant> = (value, path) => {
  const f = fieldsOf(value, path);
  return {
    id: f.number("id"),
    name: f.string("name"),
    address: f.optionalString("address"),
    phone: f.optionalString("phone"),
    image_url: f.optionalString("image_url"),
  };
};

/** GET /restaurant/{id}/menu (CatalogMenuItemDto). */
export interface MenuItem {
  id: number;
  restaurant_id: number;
  name: string;
  description: string | null;
  price: number;
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
    quantity: f.number("quantity"),
    menu_item_name: f.string("menu_item_name"),
    menu_item_description: f.optionalString("menu_item_description"),
    menu_item_image_url: f.optionalString("menu_item_image_url"),
  };
};

/** GET /cart (CartDto). */
export interface Cart {
  total_price: number;
  order_items: CartItem[];
}

export const decodeCart: Decoder<Cart> = (value, path) => {
  const f = fieldsOf(value, path);
  return {
    total_price: f.optionalNumber("total_price") ?? 0,
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
    status: f.oneOf("status", ORDER_STATUSES),
    created_at: f.optionalString("created_at"),
    items: f.arrayOrEmpty("items", decodeOrderItem),
    restaurant_id: f.optionalNumber("restaurant_id"),
    cancel_reason: f.optionalString("cancel_reason"),
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

// ---- Admin ----

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
