package com.laioffer.onlineorder.fulfilment.dispatch;


import com.fasterxml.jackson.annotation.JsonFormat;


import java.time.LocalDateTime;


/**
 * A delivery as the admin endpoints return it.
 *
 * @param orderNo the order's public number, written as a JSON string (Snowflake ids exceed 2^53); absent for a
 *                delivery that only version 1 order events reached, deliveries from before V14 included
 */
public record DeliveryDto(
        Long id,
        Long orderId,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        Long orderNo,
        Long customerId,
        Long restaurantId,
        Long riderId,
        String riderName,
        String status,
        LocalDateTime assignedAt,
        LocalDateTime pickedUpAt,
        LocalDateTime deliveredAt,
        LocalDateTime cancelledAt
) {

    public DeliveryDto(DeliveryEntity delivery, String riderName) {
        this(
                delivery.id(),
                delivery.orderId(),
                delivery.orderNo(),
                delivery.customerId(),
                delivery.restaurantId(),
                delivery.riderId(),
                riderName,
                delivery.status(),
                delivery.assignedAt(),
                delivery.pickedUpAt(),
                delivery.deliveredAt(),
                delivery.cancelledAt()
        );
    }
}
