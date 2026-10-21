import type { CurrencyCode } from "../api/currency";
import { minorDigits } from "./format";

/** The text value of a form field (FormData entries can also be files), or "". */
export const formText = (form: FormData, name: string): string => {
  const value = form.get(name);
  return typeof value === "string" ? value : "";
};

/**
 * An amount typed into a money field in `currency`: digits with at most the currency's
 * decimals ("4", "4.5", "4.50" in USD; whole numbers in JPY), as a number; null for
 * anything else. The backend refuses more decimals than the currency has.
 */
export const parseAmountInput = (value: string, currency: CurrencyCode): number | null => {
  const trimmed = value.trim();
  const digits = minorDigits(currency);
  const pattern = digits === 0 ? /^\d+$/ : new RegExp(`^\\d+(\\.\\d{1,${digits}})?$`);
  return pattern.test(trimmed) ? Number(trimmed) : null;
};

/** How a money field in `currency` wants its amount, for a form's error: "with at most two decimals". */
export const amountInputRule = (currency: CurrencyCode): string =>
  minorDigits(currency) === 0 ? "as a whole number" : "with at most two decimals";

/** An amount `parseAmountInput` accepts in `currency`, for a form's error: "4.50", or "5" in JPY. */
export const amountInputExample = (currency: CurrencyCode): string =>
  minorDigits(currency) === 0 ? "5" : "4.50";

/**
 * Whether `amount` is more than zero and less than `total`, compared in integer
 * hundredths: the rule of a counter-offer and of a partial arbitration.
 */
export const isPartialAmount = (amount: number, total: number): boolean => {
  const cents = Math.round(amount * 100);
  return cents > 0 && cents < Math.round(total * 100);
};
