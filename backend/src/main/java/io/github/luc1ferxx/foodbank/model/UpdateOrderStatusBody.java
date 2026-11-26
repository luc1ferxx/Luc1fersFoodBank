package io.github.luc1ferxx.foodbank.model;


import jakarta.validation.constraints.NotBlank;


public record UpdateOrderStatusBody(
        @NotBlank
        String status
) {
}
