package com.laioffer.onlineorder.staff;


import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;


/**
 * POST /admin/riders. Email and password are validated like POST /signup. The rider's display name is the first and
 * last name (the email when both are blank).
 */
public record CreateRiderBody(
        @Email
        @NotBlank
        String email,
        @NotBlank
        String password,
        String firstName,
        String lastName,
        @Size(max = 40)
        String phone
) {
}
