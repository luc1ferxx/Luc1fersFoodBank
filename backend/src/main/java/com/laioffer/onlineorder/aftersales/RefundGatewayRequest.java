package com.laioffer.onlineorder.aftersales;


import com.laioffer.onlineorder.money.Money;


import java.util.Objects;


/**
 * One call of the refund gateway (ADR 0002 sections 9 and D16).
 *
 * @param gatewayKey       the idempotency key, the same for every attempt of a refund ("after-sales-case:" + case id)
 * @param orderId          the order whose payment is refunded
 * @param amount           the amount to refund, the case's approved amount, more than zero
 * @param attempt          the number of this call among the refund's attempts, from 1 (a reclaim counts as one)
 * @param paymentReference the capture to refund (order_payments.provider_reference)
 */
public record RefundGatewayRequest(
        String gatewayKey,
        Long orderId,
        Money amount,
        int attempt,
        String paymentReference
) {

    public RefundGatewayRequest {
        Objects.requireNonNull(gatewayKey, "gatewayKey");
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(paymentReference, "paymentReference");
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("A refund is of more than zero, got " + amount);
        }
        if (attempt < 1) {
            throw new IllegalArgumentException("Attempts count from 1, got " + attempt);
        }
    }
}
