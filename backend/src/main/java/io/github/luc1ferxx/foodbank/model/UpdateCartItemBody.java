package io.github.luc1ferxx.foodbank.model;


import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;


public record UpdateCartItemBody(
        @NotNull
        @PositiveOrZero
        Integer quantity
) {
}
