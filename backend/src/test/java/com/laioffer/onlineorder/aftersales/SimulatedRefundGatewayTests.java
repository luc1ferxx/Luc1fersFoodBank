package com.laioffer.onlineorder.aftersales;


import com.laioffer.onlineorder.money.Money;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;


/**
 * The simulated refund gateway (ADR 0002 section 9) and the request and results it speaks: a reference derived from
 * the gateway key alone, so a repeated call answers with the same refund, and the failing first attempts for demos.
 */
class SimulatedRefundGatewayTests {

    private static final String KEY = "after-sales-case:501";


    @Test
    void refund_shouldSucceedWithAReferenceOfTheGatewayKey() {
        SimulatedRefundGateway gateway = new SimulatedRefundGateway(0);

        RefundGatewayResult first = gateway.refund(request(KEY, 1));
        RefundGatewayResult again = gateway.refund(request(KEY, 2));
        RefundGatewayResult other = gateway.refund(request("after-sales-case:502", 1));

        // The first 20 hexadecimal digits of SHA-256("after-sales-case:501").
        Assertions.assertEquals(new RefundGatewayResult.Succeeded("SIMREF-506638fb5a1a537fe34b"), first);
        Assertions.assertEquals(first, again, "the same key gets the same refund");
        Assertions.assertNotEquals(first, other);
        String reference = ((RefundGatewayResult.Succeeded) first).providerReference();
        Assertions.assertTrue(reference.matches("SIMREF-[0-9a-f]{20}"), reference);
    }


    @Test
    void refund_shouldFailTheConfiguredFirstAttempts() {
        SimulatedRefundGateway gateway = new SimulatedRefundGateway(2);

        Assertions.assertEquals(new RefundGatewayResult.RetryableFailure("simulated gateway timeout"),
                gateway.refund(request(KEY, 1)));
        Assertions.assertEquals(new RefundGatewayResult.RetryableFailure("simulated gateway timeout"),
                gateway.refund(request(KEY, 2)));
        Assertions.assertEquals(new RefundGatewayResult.Succeeded(SimulatedRefundGateway.reference(KEY)),
                gateway.refund(request(KEY, 3)));
    }


    @Test
    void failFirstAttempts_shouldNotBeNegative() {
        IllegalArgumentException refused = Assertions.assertThrows(
                IllegalArgumentException.class, () -> new SimulatedRefundGateway(-1));

        Assertions.assertEquals(
                "app.after-sales.refund-gateway.fail-first-attempts must not be negative, got -1", refused.getMessage());
    }


    @Test
    void request_shouldNameEverythingAndRefundMoreThanZeroFromTheFirstAttempt() {
        Money amount = Money.of("9.78", "USD");
        Assertions.assertThrows(NullPointerException.class, () -> new RefundGatewayRequest(null, 1L, amount, 1, "P"));
        Assertions.assertThrows(NullPointerException.class, () -> new RefundGatewayRequest(KEY, null, amount, 1, "P"));
        Assertions.assertThrows(NullPointerException.class, () -> new RefundGatewayRequest(KEY, 1L, null, 1, "P"));
        Assertions.assertThrows(NullPointerException.class, () -> new RefundGatewayRequest(KEY, 1L, amount, 1, null));

        IllegalArgumentException zero = Assertions.assertThrows(IllegalArgumentException.class,
                () -> new RefundGatewayRequest(KEY, 1L, Money.of("0.00", "USD"), 1, "P"));
        Assertions.assertEquals("A refund is of more than zero, got 0.00 USD", zero.getMessage());
        IllegalArgumentException noAttempt = Assertions.assertThrows(IllegalArgumentException.class,
                () -> new RefundGatewayRequest(KEY, 1L, amount, 0, "P"));
        Assertions.assertEquals("Attempts count from 1, got 0", noAttempt.getMessage());
    }


    @Test
    void results_shouldCarryTheirReferenceOrError() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> new RefundGatewayResult.Succeeded(null));
        Assertions.assertThrows(IllegalArgumentException.class, () -> new RefundGatewayResult.Succeeded(" "));
        Assertions.assertThrows(NullPointerException.class, () -> new RefundGatewayResult.RetryableFailure(null));
        Assertions.assertThrows(NullPointerException.class, () -> new RefundGatewayResult.PermanentFailure(null));
        Assertions.assertEquals("closed", new RefundGatewayResult.PermanentFailure("closed").error());
    }


    private static RefundGatewayRequest request(String gatewayKey, int attempt) {
        return new RefundGatewayRequest(gatewayKey, 1001L, Money.of("9.78", "USD"), attempt, "SIMPAY-0123");
    }
}
