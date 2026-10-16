package com.laioffer.onlineorder.fulfilment.dispatch;


import com.fasterxml.jackson.annotation.JsonInclude;


/**
 * GET /rider/me and PUT /rider/me/availability. Every field is always present, null included.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record RiderProfileDto(
        Long riderId,
        String name,
        String phone,
        boolean active,
        boolean onDuty,
        Integer activeDeliveries
) {

    public RiderProfileDto(RiderEntity rider) {
        this(rider.id(), rider.name(), rider.phone(), rider.active(), rider.onDuty(), rider.activeDeliveries());
    }
}
