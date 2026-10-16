package com.laioffer.onlineorder.fulfilment.dispatch;


import com.fasterxml.jackson.annotation.JsonInclude;


import java.time.LocalDateTime;


/**
 * A delivery as its rider sees it (GET /rider/deliveries and the rider's actions). Every field is always present, so
 * a timestamp that is not set yet is null rather than missing.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record RiderDeliveryDto(
        Long deliveryId,
        Long orderId,
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
                delivery.restaurantId(),
                restaurantName,
                delivery.status(),
                delivery.assignedAt(),
                delivery.pickedUpAt(),
                delivery.deliveredAt()
        );
    }
}
