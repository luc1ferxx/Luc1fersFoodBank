package com.laioffer.onlineorder.model;


import jakarta.validation.constraints.NotBlank;


public record UpdateOrderStatusBody(
        @NotBlank
        String status
) {
}
