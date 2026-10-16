package com.laioffer.onlineorder.staff;


import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;


/**
 * POST /admin/merchants. Email and password are validated like POST /signup.
 */
public record CreateMerchantBody(
        @Email
        @NotBlank
        String email,
        @NotBlank
        String password,
        String firstName,
        String lastName,
        @NotNull
        Long restaurantId
) {
}
