package com.laioffer.onlineorder.money;


import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;


import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import java.util.stream.Stream;


class MoneyTests {

    @Test
    void of_shouldKeepTheAmountExactlyAtScaleTwo() {
        Money price = Money.of("4.8", "USD");

        Assertions.assertEquals(new BigDecimal("4.80"), price.amount());
        Assertions.assertEquals(Currency.getInstance("USD"), price.currency());
        Assertions.assertEquals("USD", price.currencyCode());
        Assertions.assertEquals("4.80 USD", price.toString());
        // Scale 2 everywhere, so equal amounts are equal values whatever scale they were written with.
        Assertions.assertEquals(Money.of("4.80", "USD"), Money.of(new BigDecimal("4.8000"), "USD"));
        Assertions.assertEquals(Money.of("4.80", "USD").hashCode(), price.hashCode());
    }


    @Test
    void of_shouldNeverRoundSilently() {
        IllegalArgumentException tooPrecise = Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> Money.of("1.005", "USD")
        );
        Assertions.assertTrue(tooPrecise.getMessage().contains("more than 2 decimal places"), tooPrecise.getMessage());

        // A yen amount has no minor unit: 500.50 JPY is not an amount, 500.00 JPY is.
        Assertions.assertThrows(IllegalArgumentException.class, () -> Money.of("500.50", "JPY"));
        Assertions.assertEquals(new BigDecimal("500.00"), Money.of("500", "JPY").amount());
    }


    @Test
    void amountsOfTheSameCurrency_shouldAddAndMultiplyExactly() {
        // 0.1 + 0.2 and 3 x 0.1 are not 0.3 in binary floating point.
        Assertions.assertNotEquals(0.3, 0.1 + 0.2);
        Assertions.assertEquals(Money.of("0.30", "EUR"), Money.of("0.10", "EUR").plus(Money.of("0.20", "EUR")));
        Assertions.assertEquals(Money.of("0.30", "EUR"), Money.of("0.10", "EUR").times(3));
        Assertions.assertEquals(Money.of("0.00", "EUR"), Money.of("4.89", "EUR").times(0));
        Assertions.assertEquals(Money.of("14.67", "USD"), Money.of("4.89", "USD").times(3));
    }


    @Test
    void amountsOfDifferentCurrencies_shouldNeverBeCombined() {
        Money usd = Money.of("1.00", "USD");
        Money eur = Money.of("1.00", "EUR");

        CurrencyMismatchException mismatch = Assertions.assertThrows(CurrencyMismatchException.class, () -> usd.plus(eur));
        Assertions.assertTrue(mismatch.getMessage().contains("1.00 USD"), mismatch.getMessage());
        Assertions.assertTrue(mismatch.getMessage().contains("1.00 EUR"), mismatch.getMessage());
        Assertions.assertThrows(CurrencyMismatchException.class, () -> eur.requireSameCurrency(usd));
        Assertions.assertDoesNotThrow(() -> usd.requireSameCurrency(Money.of("2.00", "USD")));
        Assertions.assertNotEquals(usd, eur);
    }


    @Test
    void minus_shouldSubtractExactlyAndMayGoBelowZero() {
        // 0.3 - 0.1 is not 0.2 in binary floating point.
        Assertions.assertNotEquals(0.2, 0.3 - 0.1);
        Assertions.assertEquals(Money.of("0.20", "EUR"), Money.of("0.30", "EUR").minus(Money.of("0.10", "EUR")));
        Assertions.assertEquals(Money.of("6.65", "USD"), Money.of("10.00", "USD").minus(Money.of("3.35", "USD")));
        Assertions.assertTrue(Money.of("4.89", "USD").minus(Money.of("4.890", "USD")).isZero());
        Assertions.assertEquals(Money.of("-6.65", "USD"), Money.of("3.35", "USD").minus(Money.of("10.00", "USD")));
        Assertions.assertEquals(Money.of("300", "JPY"), Money.of("1000", "JPY").minus(Money.of("700", "JPY")));
    }


    @Test
    void minus_shouldRefuseAnotherCurrencyAndOverflow() {
        CurrencyMismatchException mismatch = Assertions.assertThrows(
                CurrencyMismatchException.class,
                () -> Money.of("5.00", "USD").minus(Money.of("1.00", "EUR"))
        );
        Assertions.assertTrue(mismatch.getMessage().contains("1.00 EUR"), mismatch.getMessage());
        Money lowest = new Money(Money.MAX_AMOUNT.negate(), Currency.getInstance("USD"));
        Assertions.assertThrows(MoneyOverflowException.class, () -> lowest.minus(Money.of("0.01", "USD")));
        Assertions.assertThrows(NullPointerException.class, () -> Money.of("1.00", "USD").minus(null));
    }


    @Test
    void comparisons_shouldOrderAmountsOfOneCurrencyByValue() {
        Money five = Money.of("5.00", "USD");
        Money alsoFive = Money.of(new BigDecimal("5.0"), "USD");
        Money six = Money.of("6.00", "USD");

        // 5.0 and 5.00 are different BigDecimals but the same amount.
        Assertions.assertNotEquals(new BigDecimal("5.0"), new BigDecimal("5.00"));
        Assertions.assertEquals(0, five.compareTo(alsoFive));
        Assertions.assertEquals(five, alsoFive);
        Assertions.assertTrue(five.compareTo(six) < 0);
        Assertions.assertTrue(six.compareTo(five) > 0);

        Assertions.assertTrue(six.isGreaterThan(five));
        Assertions.assertFalse(five.isGreaterThan(alsoFive));
        Assertions.assertFalse(five.isGreaterThan(six));
        Assertions.assertTrue(six.isGreaterThanOrEqualTo(five));
        Assertions.assertTrue(five.isGreaterThanOrEqualTo(alsoFive));
        Assertions.assertFalse(five.isGreaterThanOrEqualTo(six));
        Assertions.assertTrue(five.isLessThan(six));
        Assertions.assertFalse(five.isLessThan(alsoFive));
        Assertions.assertFalse(six.isLessThan(five));
        Assertions.assertTrue(five.isLessThanOrEqualTo(six));
        Assertions.assertTrue(five.isLessThanOrEqualTo(alsoFive));
        Assertions.assertFalse(six.isLessThanOrEqualTo(five));

        Assertions.assertEquals(
                List.of(Money.of("-1.00", "USD"), Money.zero("USD"), five, six),
                Stream.of(six, Money.zero("USD"), five, Money.of("-1.00", "USD")).sorted().toList()
        );
    }


    @Test
    void comparisons_shouldRefuseAmountsOfDifferentCurrencies() {
        Money usd = Money.of("1.00", "USD");
        Money eur = Money.of("2.00", "EUR");

        Assertions.assertThrows(CurrencyMismatchException.class, () -> usd.compareTo(eur));
        Assertions.assertThrows(CurrencyMismatchException.class, () -> usd.isGreaterThan(eur));
        Assertions.assertThrows(CurrencyMismatchException.class, () -> usd.isGreaterThanOrEqualTo(eur));
        Assertions.assertThrows(CurrencyMismatchException.class, () -> usd.isLessThan(eur));
        Assertions.assertThrows(CurrencyMismatchException.class, () -> usd.isLessThanOrEqualTo(eur));
        Assertions.assertThrows(NullPointerException.class, () -> usd.compareTo(null));
    }


    @Test
    void isPositive_shouldOnlyHoldAboveZero() {
        Assertions.assertTrue(Money.of("0.01", "USD").isPositive());
        Assertions.assertTrue(Money.of("1", "JPY").isPositive());
        Assertions.assertFalse(Money.zero("USD").isPositive());
        Assertions.assertFalse(Money.of("-0.01", "USD").isPositive());
    }


    /**
     * HALF_EVEN: a tie goes to the even neighbour (0.125 to 0.12, 0.135 to 0.14), anything else to the nearest
     * amount; yen round to whole yen.
     */
    @ParameterizedTest
    @CsvSource({
            "0.125, USD, 0.12",
            "0.135, USD, 0.14",
            "0.1251, USD, 0.13",
            "-0.125, USD, -0.12",
            "2.675, EUR, 2.68",
            "2.665, EUR, 2.66",
            "500.5, JPY, 500.00",
            "501.5, JPY, 502.00",
            "4.89, USD, 4.89"
    })
    void rounded_shouldRoundHalfEvenToTheCurrencysMinorUnit(String amount, String currency, String expected) {
        Money rounded = Money.rounded(new BigDecimal(amount), Currency.getInstance(currency));

        Assertions.assertEquals(new BigDecimal(expected), rounded.amount());
        Assertions.assertEquals(Money.ROUNDING, java.math.RoundingMode.HALF_EVEN);
    }


    @Test
    void multiply_shouldRoundTheProductHalfEven() {
        // 10% off 0.25 is 0.225: a tie, rounded to the even 0.22.
        Assertions.assertEquals(Money.of("0.22", "USD"), Money.of("0.25", "USD").multiply(new BigDecimal("0.9")));
        // 8.25% tax on 19.99 is 1.649175: 1.65.
        Assertions.assertEquals(Money.of("1.65", "USD"), Money.of("19.99", "USD").multiply(new BigDecimal("0.0825")));
        Assertions.assertEquals(Money.of("1100", "JPY"), Money.of("1000", "JPY").multiply(new BigDecimal("1.1")));
    }


    @Test
    void amountsBeyondNumeric12Comma2_shouldOverflow() {
        Money largest = new Money(Money.MAX_AMOUNT, Currency.getInstance("USD"));

        Assertions.assertEquals(new BigDecimal("9999999999.99"), largest.amount());
        Assertions.assertThrows(MoneyOverflowException.class, () -> largest.plus(Money.of("0.01", "USD")));
        Assertions.assertThrows(MoneyOverflowException.class, () -> Money.of("10.00", "USD").times(2_000_000_000));
        Assertions.assertThrows(MoneyOverflowException.class, () -> Money.of("-10000000000", "USD"));
    }


    @ParameterizedTest
    @ValueSource(strings = {"usd", "US", "USDX", "U5D", "ZZZ", ""})
    void currency_shouldOnlyAcceptIso4217Codes(String code) {
        Assertions.assertThrows(IllegalArgumentException.class, () -> Money.currency(code));
    }


    @ParameterizedTest
    @ValueSource(strings = {"BHD", "KWD", "XAU", "XXX"})
    void currency_shouldRejectCurrenciesThatDoNotFitTwoDecimalPlaces(String code) {
        IllegalArgumentException unsupported = Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> Money.zero(code)
        );
        Assertions.assertTrue(unsupported.getMessage().contains("not supported"), unsupported.getMessage());
    }


    @Test
    void zero_shouldBeZeroInItsCurrency() {
        Money zero = Money.zero("JPY");

        Assertions.assertTrue(zero.isZero());
        Assertions.assertFalse(Money.of("0.01", "USD").isZero());
        Assertions.assertEquals(new BigDecimal("0.00"), zero.amount());
        Assertions.assertEquals("JPY", zero.currencyCode());
        Assertions.assertEquals(Money.zero(Money.LEGACY_CURRENCY), Money.zero("USD"));
    }


    @Test
    void constructor_shouldRequireAnAmountAndACurrency() {
        Assertions.assertThrows(NullPointerException.class, () -> new Money(null, Currency.getInstance("USD")));
        Assertions.assertThrows(NullPointerException.class, () -> new Money(BigDecimal.ONE, null));
        Assertions.assertThrows(NullPointerException.class, () -> Money.of("1.00", "USD").multiply(null));
        Assertions.assertThrows(IllegalArgumentException.class, () -> Money.of("1.00", null));
    }
}
