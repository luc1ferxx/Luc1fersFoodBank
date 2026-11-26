package io.github.luc1ferxx.foodbank.messaging;


/**
 * @param status the delivery status after the event: ASSIGNED, PICKED_UP, DELIVERED or CANCELLED
 */
public record DeliveryEventPayload(
        Long orderId,
        Long customerId,
        Long restaurantId,
        Long riderId,
        String riderName,
        String status
) {
}
