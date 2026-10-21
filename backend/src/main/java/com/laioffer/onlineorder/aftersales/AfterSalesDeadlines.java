package com.laioffer.onlineorder.aftersales;


import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;


import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;


/**
 * The after-sales time limits (ADR 0002 section 6, app.after-sales.*): how long after its completion an order can get
 * a request, and how long each open status waits for its answer. The single writer sets deadline_at from them every
 * time a case enters a status; a changed value applies to the steps taken after the change.
 */
@Component
public final class AfterSalesDeadlines {

    private final Duration requestWindow;
    private final Duration merchantResponseTimeout;
    private final Duration customerResponseTimeout;
    private final Duration arbitrationTimeout;


    public AfterSalesDeadlines(
            @Value("${app.after-sales.request-window:24h}") Duration requestWindow,
            @Value("${app.after-sales.merchant-response-timeout:24h}") Duration merchantResponseTimeout,
            @Value("${app.after-sales.customer-response-timeout:48h}") Duration customerResponseTimeout,
            @Value("${app.after-sales.arbitration-timeout:72h}") Duration arbitrationTimeout
    ) {
        this.requestWindow = requirePositive(requestWindow, "app.after-sales.request-window");
        this.merchantResponseTimeout = requirePositive(
                merchantResponseTimeout, "app.after-sales.merchant-response-timeout");
        this.customerResponseTimeout = requirePositive(
                customerResponseTimeout, "app.after-sales.customer-response-timeout");
        this.arbitrationTimeout = requirePositive(arbitrationTimeout, "app.after-sales.arbitration-timeout");
    }


    public Duration requestWindow() {
        return requestWindow;
    }


    /**
     * The last moment an order completed at {@code completedAt} can get a request; a second request after a withdrawal
     * has the same window (section 20).
     */
    public LocalDateTime requestDeadline(LocalDateTime completedAt) {
        return completedAt.plus(requestWindow);
    }


    /**
     * The deadline a case entering {@code status} at {@code enteredAt} gets: the merchant's for PENDING_MERCHANT, the
     * customer's for COUNTER_OFFERED and REJECTED, the platform's for IN_ARBITRATION, and none (null) for REFUNDING,
     * REFUNDED and CLOSED.
     */
    public LocalDateTime deadlineAt(AfterSalesStatus status, LocalDateTime enteredAt) {
        Objects.requireNonNull(enteredAt, "enteredAt");
        return switch (status) {
            case PENDING_MERCHANT -> enteredAt.plus(merchantResponseTimeout);
            case COUNTER_OFFERED, REJECTED -> enteredAt.plus(customerResponseTimeout);
            case IN_ARBITRATION -> enteredAt.plus(arbitrationTimeout);
            case REFUNDING, REFUNDED, CLOSED -> null;
        };
    }


    private static Duration requirePositive(Duration duration, String property) {
        if (duration == null || duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException(property + " must be positive, got " + duration);
        }
        return duration;
    }
}
