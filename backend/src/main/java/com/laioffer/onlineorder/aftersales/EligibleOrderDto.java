package com.laioffer.onlineorder.aftersales;


import com.fasterxml.jackson.annotation.JsonFormat;


import java.time.LocalDateTime;


/**
 * An order of the customer that can get an after-sales request now.
 *
 * @param orderNo         the order's public number, written as a JSON string (Snowflake ids exceed 2^53)
 * @param attemptNo       the attempt a request would be: 1, or 2 (the last one) after the customer withdrew the first
 * @param requestDeadline the end of the order's request window
 */
public record EligibleOrderDto(
        Long orderId,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        Long orderNo,
        Integer attemptNo,
        LocalDateTime requestDeadline
) {
}
