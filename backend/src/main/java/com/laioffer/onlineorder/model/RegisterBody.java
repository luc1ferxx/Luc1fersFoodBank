package com.laioffer.onlineorder.model;


import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;


public record RegisterBody(
        @Email
        @NotBlank
        String email,
        @NotBlank
        String password,
        String firstName,
        String lastName
) {
}
