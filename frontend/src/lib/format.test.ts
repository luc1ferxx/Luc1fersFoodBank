import { describe, expect, it } from "vitest";
import { LEGACY_CURRENCY, currencyCode, isCurrencyCode } from "../api/currency";
import { EMPTY_CART, formatMoney, lineTotal } from "./format";

describe("formatMoney", () => {
  it("writes an amount with its currency's symbol and decimals", () => {
    expect(formatMoney(12.78, currencyCode("USD"))).toBe("$12.78");
    expect(formatMoney(11, currencyCode("USD"))).toBe("$11.00");
    expect(formatMoney(0.5, currencyCode("EUR"))).toBe("€0.50");
    expect(formatMoney(4.5, currencyCode("GBP"))).toBe("£4.50");
    // The yen has no minor unit.
    expect(formatMoney(950, currencyCode("JPY"))).toBe("¥950");
    expect(formatMoney(1234567.5, currencyCode("USD"))).toBe("$1,234,567.50");
    expect(formatMoney(0, LEGACY_CURRENCY)).toBe("$0.00");
  });

  it("shows an empty cart in the legacy currency until it holds an item", () => {
    expect(formatMoney(EMPTY_CART.total_price, EMPTY_CART.currency)).toBe("$0.00");
  });
});

describe("lineTotal", () => {
  it("multiplies in integer hundredths, not in floating point", () => {
    expect(0.1 * 3).not.toBe(0.3);
    expect(lineTotal(0.1, 3)).toBe(0.3);
    expect(lineTotal(6.39, 2)).toBe(12.78);
    expect(lineTotal(1.15, 3)).toBe(3.45);
    expect(lineTotal(4.89, 0)).toBe(0);
    expect(formatMoney(lineTotal(0.1, 3), currencyCode("EUR"))).toBe("€0.30");
  });
});

describe("currency codes", () => {
  it("are three upper-case letters", () => {
    expect(isCurrencyCode("USD")).toBe(true);
    expect(isCurrencyCode("usd")).toBe(false);
    expect(isCurrencyCode("US")).toBe(false);
    expect(isCurrencyCode(840)).toBe(false);
    expect(() => currencyCode("EURO")).toThrow(RangeError);
  });
});
