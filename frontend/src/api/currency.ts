/**
 * ISO 4217 currency codes as the backend sends them next to every amount ("price" +
 * "currency", "total_price" + "currency"). A cart and an order have one currency, the
 * currency of their restaurant.
 */

declare const currencyCodeBrand: unique symbol;

/** Three upper-case letters, checked when a response is decoded (see `fieldsOf().currency`). */
export type CurrencyCode = string & { readonly [currencyCodeBrand]: true };

export const isCurrencyCode = (value: unknown): value is CurrencyCode =>
  typeof value === "string" && /^[A-Z]{3}$/.test(value);

/** `value` as a CurrencyCode; throws for anything but three upper-case letters. */
export const currencyCode = (value: string): CurrencyCode => {
  if (!isCurrencyCode(value)) {
    throw new RangeError(`Not an ISO 4217 currency code: ${JSON.stringify(value)}`);
  }
  return value;
};

/**
 * The currency of a response without one: every amount was USD before the backend
 * sent currencies, so a pod of that release still answers in USD during a rollout.
 */
export const LEGACY_CURRENCY: CurrencyCode = currencyCode("USD");
