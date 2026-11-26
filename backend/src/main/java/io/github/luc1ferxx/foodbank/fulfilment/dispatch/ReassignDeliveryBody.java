package io.github.luc1ferxx.foodbank.fulfilment.dispatch;


import jakarta.validation.constraints.NotNull;


/**
 * POST /admin/deliveries/{deliveryId}/reassign: the rider to move the delivery to (active and on duty).
 */
public record ReassignDeliveryBody(
        @NotNull
        Long riderId
) {
}
