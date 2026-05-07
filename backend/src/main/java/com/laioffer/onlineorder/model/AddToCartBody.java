package com.laioffer.onlineorder.model;


import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;


public record AddToCartBody(
        @NotNull
        @Positive
        Long menuId
) {
}
