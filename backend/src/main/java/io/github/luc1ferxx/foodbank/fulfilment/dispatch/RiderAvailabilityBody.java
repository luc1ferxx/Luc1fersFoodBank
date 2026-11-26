package io.github.luc1ferxx.foodbank.fulfilment.dispatch;


import jakarta.validation.constraints.NotNull;


public record RiderAvailabilityBody(
        @NotNull
        Boolean onDuty
) {
}
