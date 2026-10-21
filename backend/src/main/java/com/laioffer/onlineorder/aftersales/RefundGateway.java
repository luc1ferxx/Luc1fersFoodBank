package com.laioffer.onlineorder.aftersales;


/**
 * The payment provider's refund API, as the refund executor sees it (ADR 0002 D8 and section 9). The executor calls it
 * outside any transaction, holding the refund's lease, and stops waiting after app.after-sales.refund-gateway.timeout.
 *
 * <p>An implementation must be idempotent per {@link RefundGatewayRequest#gatewayKey()}: the executor calls it again
 * with the same key after a failure, a timeout or an expired lease, and a call for a key that was already refunded must
 * answer with that refund instead of refunding again. Any {@link RuntimeException} it throws is treated as a
 * {@link RefundGatewayResult.RetryableFailure}.
 */
public interface RefundGateway {

    /**
     * Refunds {@link RefundGatewayRequest#amount()} of the capture {@link RefundGatewayRequest#paymentReference()}.
     *
     * @return the outcome; null counts as a retryable failure
     */
    RefundGatewayResult refund(RefundGatewayRequest request);
}
