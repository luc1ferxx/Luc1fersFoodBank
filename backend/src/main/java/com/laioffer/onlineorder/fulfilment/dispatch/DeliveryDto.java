package com.laioffer.onlineorder.fulfilment.dispatch;


import java.time.LocalDateTime;


public record DeliveryDto(
        Long id,
        Long orderId,
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
