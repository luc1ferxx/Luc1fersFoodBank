export const EMPTY_CART = Object.freeze({
  order_items: [],
  total_price: 0,
});

export const toArray = (value) => (Array.isArray(value) ? value : []);

export const formatCurrency = (value) => `$${Number(value || 0).toFixed(2)}`;

export const formatDateTime = (value) => {
  if (!value) {
    return "Pending timestamp";
  }

  const date = new Date(value);

  if (Number.isNaN(date.getTime())) {
    return value;
  }

  return date.toLocaleString();
};

export const formatCardNumberInput = (value = "") =>
  value
    .replace(/\D/g, "")
    .slice(0, 16)
    .replace(/(\d{4})(?=\d)/g, "$1 ");

export const formatExpiryInput = (value = "") => {
  const digits = value.replace(/\D/g, "").slice(0, 4);

  if (digits.length <= 2) {
    return digits;
  }

  return `${digits.slice(0, 2)}/${digits.slice(2)}`;
};

export const formatSecurityCode = (value = "") => value.replace(/\D/g, "").slice(0, 4);

export const countCartItems = (cart) =>
  (cart?.order_items || []).reduce((sum, item) => sum + (item.quantity || 0), 0);

export const buildCartItemMap = (cart) =>
  (cart?.order_items || []).reduce((cartMap, item) => {
    cartMap[item.menu_item_id] = {
      orderItemId: item.order_item_id,
      quantity: item.quantity,
    };
    return cartMap;
  }, {});

export const errorCopy = (error, fallback) => {
  if (!error) {
    return fallback;
  }

  const prefix = error.status ? `${error.status}: ` : "";
  const trace = error.traceId ? ` Trace ${error.traceId}.` : "";
  return `${prefix}${error.message || fallback}${trace}`;
};
