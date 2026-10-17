package com.laioffer.onlineorder.money;


import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;
import java.util.regex.Pattern;


/**
 * An amount of money in one ISO 4217 currency. Cart, checkout and payment code do all their arithmetic with this
 * type; amounts never pass through {@code double} or {@code float}.
 *
 * <ul>
 *     <li>The amount is a {@link BigDecimal} with scale 2, the scale of the {@code NUMERIC(12,2)} columns that store
 *     it, and at most {@link #MAX_AMOUNT} in absolute value (twelve digits). Creating a value with more decimal
 *     places, or more minor digits than its currency has (a JPY amount with cents), is an error: {@link #of} never
 *     rounds silently.</li>
 *     <li>Rounding, where an operation needs it ({@link #rounded}, {@link #multiply}), is {@link #ROUNDING}:
 *     HALF_EVEN (banker's rounding) to the currency's minor unit. Ties go to the even neighbour, so rounding a
 *     column of amounts carries no upward bias. Sums and multiples by a quantity are exact and never round.</li>
 *     <li>Only currencies with 0 to 2 minor digits are supported (USD, EUR, JPY, ...); a currency with 3 (BHD, KWD)
 *     or none at all (XAU) does not fit the storage scale and is rejected.</li>
 *     <li>Arithmetic combines amounts of one currency only: adding EUR to USD throws
 *     {@link CurrencyMismatchException}. A cart and an order are single-currency (they are single-restaurant, and a
 *     restaurant prices in one currency), so their totals are sums in one currency by construction.</li>
 * </ul>
 */
public record Money(BigDecimal amount, Currency currency) {

    /**
     * The scale of every amount: the scale of the NUMERIC(12,2) money columns.
     */
    public static final int SCALE = 2;

    public static final RoundingMode ROUNDING = RoundingMode.HALF_EVEN;

    /**
     * The largest amount a NUMERIC(12,2) column stores.
     */
    public static final BigDecimal MAX_AMOUNT = new BigDecimal("9999999999.99");

    /**
     * The currency of every amount stored before money carried a currency (migration V11), of events published
     * before then, and of a cart that never held an item.
     */
    public static final Currency LEGACY_CURRENCY = Currency.getInstance("USD");

    private static final Pattern CURRENCY_CODE = Pattern.compile("[A-Z]{3}");


    public Money {
        Objects.requireNonNull(amount, "amount");
        requireSupported(Objects.requireNonNull(currency, "currency"));
        BigDecimal normalized;
        try {
            normalized = amount.setScale(SCALE, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException(
                    "Amount " + amount.toPlainString() + " " + currency.getCurrencyCode() + " has more than " + SCALE
                            + " decimal places",
                    ex
            );
        }
        if (normalized.stripTrailingZeros().scale() > currency.getDefaultFractionDigits()) {
            throw new IllegalArgumentException(
                    "Amount " + amount.toPlainString() + " " + currency.getCurrencyCode() + " has more decimal places "
                            + "than the currency's " + currency.getDefaultFractionDigits()
            );
        }
        if (normalized.abs().compareTo(MAX_AMOUNT) > 0) {
            throw new MoneyOverflowException(
                    "Amount " + normalized.toPlainString() + " " + currency.getCurrencyCode() + " exceeds "
                            + MAX_AMOUNT.toPlainString()
            );
        }
        amount = normalized;
    }


    /**
     * Exactly {@code amount} in {@code currencyCode}; see {@link #Money(BigDecimal, Currency)} for what is rejected.
     */
    public static Money of(BigDecimal amount, String currencyCode) {
        return new Money(amount, currency(currencyCode));
    }


    public static Money of(String amount, String currencyCode) {
        return of(new BigDecimal(amount), currencyCode);
    }


    public static Money zero(Currency currency) {
        return new Money(BigDecimal.ZERO, currency);
    }


    public static Money zero(String currencyCode) {
        return zero(currency(currencyCode));
    }


    /**
     * {@code amount} rounded with {@link #ROUNDING} to the minor unit of {@code currency} (cents for USD, whole
     * yen for JPY).
     */
    public static Money rounded(BigDecimal amount, Currency currency) {
        Objects.requireNonNull(amount, "amount");
        requireSupported(Objects.requireNonNull(currency, "currency"));
        return new Money(amount.setScale(currency.getDefaultFractionDigits(), ROUNDING), currency);
    }


    /**
     * The supported ISO 4217 currency with this code: three upper-case letters, 0 to 2 minor digits.
     *
     * @throws IllegalArgumentException for anything else
     */
    public static Currency currency(String currencyCode) {
        if (currencyCode == null || !CURRENCY_CODE.matcher(currencyCode).matches()) {
            throw new IllegalArgumentException("Currency must be an ISO 4217 code of three upper-case letters, got "
                    + currencyCode);
        }
        Currency currency;
        try {
            currency = Currency.getInstance(currencyCode);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unknown ISO 4217 currency " + currencyCode, ex);
        }
        requireSupported(currency);
        return currency;
    }


    private static void requireSupported(Currency currency) {
        int minorDigits = currency.getDefaultFractionDigits();
        if (minorDigits < 0 || minorDigits > SCALE) {
            throw new IllegalArgumentException(
                    "Currency " + currency.getCurrencyCode() + " is not supported: amounts are stored with "
                            + SCALE + " decimal places and it has "
                            + (minorDigits < 0 ? "no minor unit" : minorDigits + " minor digits")
            );
        }
    }


    public String currencyCode() {
        return currency.getCurrencyCode();
    }


    /**
     * The exact sum.
     *
     * @throws CurrencyMismatchException when {@code other} is in another currency
     * @throws MoneyOverflowException    when the sum exceeds {@link #MAX_AMOUNT}
     */
    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }


    /**
     * This amount {@code quantity} times, exactly: the total of a cart line.
     *
     * @throws MoneyOverflowException when the product exceeds {@link #MAX_AMOUNT}
     */
    public Money times(int quantity) {
        return new Money(amount.multiply(BigDecimal.valueOf(quantity)), currency);
    }


    /**
     * This amount scaled by {@code factor} (a rate, such as a discount or a tax), rounded with {@link #ROUNDING} to
     * the currency's minor unit.
     */
    public Money multiply(BigDecimal factor) {
        return rounded(amount.multiply(Objects.requireNonNull(factor, "factor")), currency);
    }


    public boolean isZero() {
        return amount.signum() == 0;
    }


    /**
     * @throws CurrencyMismatchException when {@code other} is in another currency than this amount
     */
    public void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "other");
        if (!currency.equals(other.currency)) {
            throw new CurrencyMismatchException(this, other);
        }
    }


    @Override
    public String toString() {
        return amount.toPlainString() + " " + currency.getCurrencyCode();
    }
}
