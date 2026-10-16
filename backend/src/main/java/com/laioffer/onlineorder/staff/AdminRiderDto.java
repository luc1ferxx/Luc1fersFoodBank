package com.laioffer.onlineorder.staff;


import com.fasterxml.jackson.annotation.JsonInclude;


import java.time.LocalDateTime;


/**
 * A rider as admins see it. Every field is always present: customer_id, email, first_name and last_name are null for a
 * rider created before rider logins existed.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AdminRiderDto(
        Long riderId,
        Long customerId,
        String email,
        String firstName,
        String lastName,
        String name,
        String phone,
        boolean active,
        boolean onDuty,
        Integer activeDeliveries,
        LocalDateTime createdAt
) {
}
