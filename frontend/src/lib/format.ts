import { ApiError } from "../api/client";
import { type CurrencyCode, LEGACY_CURRENCY } from "../api/currency";
import type { Cart } from "../api/dto";

export const EMPTY_CART: Cart = Object.freeze({
  order_items: [],
  total_price: 0,
  currency: LEGACY_CURRENCY,
});

/** Money is written the way the (English) UI copy is: $12.50, €0.50, ¥950. */
const MONEY_LOCALE = "en-US";

const moneyFormats = new Map<CurrencyCode, Intl.NumberFormat>();

/**
 * `amount` in `currency`, with the currency's symbol and its own number of decimals
 * (two for USD and EUR, none for JPY). The one place the UI formats money.
 */
export const formatMoney = (amount: number, currency: CurrencyCode): string => {
  let format = moneyFormats.get(currency);
  if (!format) {
    format = new Intl.NumberFormat(MONEY_LOCALE, { style: "currency", currency });
    moneyFormats.set(currency, format);
  }
  return format.format(amount);
};

/**
 * `price` times `quantity`, computed in integer hundredths: amounts have two decimals,
 * and 0.1 * 3 is 0.30000000000000004 in floating point.
 */
export const lineTotal = (price: number, quantity: number): number =>
  (Math.round(price * 100) * quantity) / 100;

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

/**
 * The time left until `deadline` (a backend LocalDateTime, read as local time) at
 * `now`: "1d 4h", "3h 12m", "2m 05s", "40s"; null once it passed or when it does not
 * parse.
 */
export const timeLeft = (deadline: string, now: number): string | null => {
  const left = Date.parse(deadline) - now;
  if (Number.isNaN(left) || left <= 0) {
    return null;
  }
  const seconds = Math.floor(left / 1000);
  const days = Math.floor(seconds / 86400);
  const hours = Math.floor((seconds % 86400) / 3600);
  const minutes = Math.floor((seconds % 3600) / 60);
  if (days > 0) {
    return `${days}d ${hours}h`;
  }
  if (hours > 0) {
    return `${hours}h ${minutes}m`;
  }
  if (minutes > 0) {
    return `${minutes}m ${String(seconds % 60).padStart(2, "0")}s`;
  }
  return `${seconds}s`;
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
