package com.laioffer.onlineorder.fulfilment.dispatch;


import jakarta.validation.constraints.NotNull;


public record RiderAvailabilityBody(
        @NotNull
        Boolean onDuty
) {
}
