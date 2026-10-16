import { ApiError } from "../api/client";
import type { Cart } from "../api/dto";

export const EMPTY_CART: Cart = Object.freeze({
  order_items: [],
  total_price: 0,
});

export const formatCurrency = (value: number | null | undefined): string =>
  `$${Number(value || 0).toFixed(2)}`;

export const formatDateTime = (value: string | null | undefined): string => {
  if (!value) {
    return "Pending timestamp";
  }

  const date = new Date(value);

  if (Number.isNaN(date.getTime())) {
    return value;
  }

  return date.toLocaleString();
};

export const formatCardNumberInput = (value = ""): string =>
  value
    .replace(/\D/g, "")
    .slice(0, 16)
    .replace(/(\d{4})(?=\d)/g, "$1 ");

export const formatExpiryInput = (value = ""): string => {
  const digits = value.replace(/\D/g, "").slice(0, 4);

  if (digits.length <= 2) {
    return digits;
  }

  return `${digits.slice(0, 2)}/${digits.slice(2)}`;
};

export const formatSecurityCode = (value = ""): string => value.replace(/\D/g, "").slice(0, 4);

export const countCartItems = (cart: Cart): number =>
  cart.order_items.reduce((sum, item) => sum + item.quantity, 0);

export interface CartLineRef {
  orderItemId: number;
  quantity: number;
}

/** Cart lines keyed by menu item id, for the menu cards' quantity steppers. */
export const buildCartItemMap = (cart: Cart): ReadonlyMap<number, CartLineRef> =>
  new Map(
    cart.order_items.map((item) => [
      item.menu_item_id,
      { orderItemId: item.order_item_id, quantity: item.quantity },
    ]),
  );

/**
 * User-facing copy for a failed request: "<status>: <message> Trace <id>." for API
 * errors, the message for other errors, `fallback` when there is nothing better.
 */
export const errorCopy = (error: unknown, fallback: string): string => {
  if (error instanceof ApiError) {
    const prefix = error.status ? `${error.status}: ` : "";
    const trace = error.traceId ? ` Trace ${error.traceId}.` : "";
    return `${prefix}${error.message || fallback}${trace}`;
  }

  if (error instanceof Error && error.message) {
    return error.message;
  }

  return fallback;
};
