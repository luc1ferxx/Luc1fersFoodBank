package com.laioffer.onlineorder.aftersales;


import java.util.Objects;


/**
 * What the refund gateway answered (ADR 0002 section 9). The executor tells the cases apart with {@code instanceof}
 * patterns: a success completes the refund and its case, a retryable failure is tried again later while attempts are
 * left, and a permanent failure gives the refund up for an admin to look at.
 */
public sealed interface RefundGatewayResult permits RefundGatewayResult.Succeeded,
        RefundGatewayResult.RetryableFailure, RefundGatewayResult.PermanentFailure {

    /**
     * The gateway refunded the amount.
     *
     * @param providerReference the gateway's id of the refund, the same for every call with the same gateway key
     */
    record Succeeded(String providerReference) implements RefundGatewayResult {

        public Succeeded {
            if (providerReference == null || providerReference.isBlank()) {
                throw new IllegalArgumentException("A refund the gateway made has its reference");
            }
        }
    }


    /**
     * The call failed in a way that may pass (a timeout, the gateway unavailable): try again later.
     *
     * @param error what went wrong, kept as the refund's last_error
     */
    record RetryableFailure(String error) implements RefundGatewayResult {

        public RetryableFailure {
            Objects.requireNonNull(error, "error");
        }
    }


    /**
     * The gateway refused the refund for good (the capture cannot be refunded): retrying the same call cannot help.
     *
     * @param error the gateway's reason, kept as the refund's last_error
     */
    record PermanentFailure(String error) implements RefundGatewayResult {

        public PermanentFailure {
            Objects.requireNonNull(error, "error");
        }
    }
}
