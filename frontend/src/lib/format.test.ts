import { describe, expect, it } from "vitest";
import { LEGACY_CURRENCY, currencyCode, isCurrencyCode } from "../api/currency";
import { EMPTY_CART, formatMoney, lineTotal, timeLeft } from "./format";
import { isPartialAmount, parseAmountInput } from "./forms";

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

describe("timeLeft", () => {
  const now = Date.parse("2026-10-20T12:00:00");

  it("counts down to a backend LocalDateTime with the two largest units", () => {
    expect(timeLeft("2026-10-21T16:30:00", now)).toBe("1d 4h");
    expect(timeLeft("2026-10-20T15:12:59", now)).toBe("3h 12m");
    expect(timeLeft("2026-10-20T12:02:05", now)).toBe("2m 05s");
    expect(timeLeft("2026-10-20T12:00:40", now)).toBe("40s");
  });

  it("is null once the deadline passed or when it does not parse", () => {
    expect(timeLeft("2026-10-20T12:00:00", now)).toBeNull();
    expect(timeLeft("2026-10-19T12:00:00", now)).toBeNull();
    expect(timeLeft("soon", now)).toBeNull();
  });
});

describe("amount inputs", () => {
  it("accept digits with at most two decimals", () => {
    expect(parseAmountInput(" 4.5 ")).toBe(4.5);
    expect(parseAmountInput("4.50")).toBe(4.5);
    expect(parseAmountInput("12")).toBe(12);
    ["", "4.567", "-1", "1e2", "4,50", ".5", "abc"].forEach((input) =>
      expect(parseAmountInput(input)).toBeNull(),
    );
  });

  it("compare a partial amount with the total in hundredths", () => {
    expect(isPartialAmount(0.1 + 0.2, 0.31)).toBe(true);
    expect(isPartialAmount(6.39, 6.39)).toBe(false);
    expect(isPartialAmount(0, 6.39)).toBe(false);
    expect(isPartialAmount(6.38, 6.39)).toBe(true);
  });
});
