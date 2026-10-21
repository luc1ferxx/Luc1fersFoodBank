package com.laioffer.onlineorder.aftersales;


import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;


import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;


/**
 * The refund gateway of this demo, standing in for a payment provider (ADR 0002 section 9), like the simulated capture
 * of a paid checkout ({@code SIMPAY-...}). It refunds every request at once with the reference
 * {@code SIMREF-} followed by the first 20 hexadecimal digits of the SHA-256 of the gateway key: the same reference for
 * every call with the same key, so it is idempotent per key as a real gateway is.
 *
 * <p>For demos and tests, the first app.after-sales.refund-gateway.fail-first-attempts attempts of every refund
 * (default 0) fail with a retryable "simulated gateway timeout".
 */
@Component
public final class SimulatedRefundGateway implements RefundGateway {

    static final String REFERENCE_PREFIX = "SIMREF-";

    static final String SIMULATED_FAILURE = "simulated gateway timeout";

    private static final int REFERENCE_DIGITS = 20;

    private final int failFirstAttempts;


    public SimulatedRefundGateway(
            @Value("${app.after-sales.refund-gateway.fail-first-attempts:0}") int failFirstAttempts
    ) {
        if (failFirstAttempts < 0) {
            throw new IllegalArgumentException("app.after-sales.refund-gateway.fail-first-attempts must not be "
                    + "negative, got " + failFirstAttempts);
        }
        this.failFirstAttempts = failFirstAttempts;
    }


    @Override
    public RefundGatewayResult refund(RefundGatewayRequest request) {
        if (request.attempt() <= failFirstAttempts) {
            return new RefundGatewayResult.RetryableFailure(SIMULATED_FAILURE);
        }
        return new RefundGatewayResult.Succeeded(reference(request.gatewayKey()));
    }


    /**
     * The reference of the refund made for {@code gatewayKey}.
     */
    static String reference(String gatewayKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(gatewayKey.getBytes(StandardCharsets.UTF_8));
            return REFERENCE_PREFIX + HexFormat.of().formatHex(digest).substring(0, REFERENCE_DIGITS);
        } catch (NoSuchAlgorithmException ex) {
            // Every Java platform has SHA-256.
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }
}
