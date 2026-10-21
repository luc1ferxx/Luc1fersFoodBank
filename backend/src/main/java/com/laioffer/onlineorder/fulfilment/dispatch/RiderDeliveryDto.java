package com.laioffer.onlineorder.fulfilment.dispatch;


import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;


import java.time.LocalDateTime;


/**
 * A delivery as its rider sees it (GET /rider/deliveries and the rider's actions). Every field except
 * {@code order_no} is always present, so a timestamp that is not set yet is null rather than missing.
 *
 * @param orderId the internal key of the order
 * @param orderNo the order's public number to show the rider, written as a JSON string (Snowflake ids exceed 2^53);
 *                absent for a delivery that only version 1 order events reached, deliveries from before V14 included
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record RiderDeliveryDto(
        Long deliveryId,
        Long orderId,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Long orderNo,
        Long restaurantId,
        String restaurantName,
        String status,
        LocalDateTime assignedAt,
        LocalDateTime pickedUpAt,
        LocalDateTime deliveredAt
) {

    public RiderDeliveryDto(DeliveryEntity delivery, String restaurantName) {
        this(
                delivery.id(),
                delivery.orderId(),
                delivery.orderNo(),
                delivery.restaurantId(),
                restaurantName,
                delivery.status(),
                delivery.assignedAt(),
                delivery.pickedUpAt(),
                delivery.deliveredAt()
        );
    }
}
