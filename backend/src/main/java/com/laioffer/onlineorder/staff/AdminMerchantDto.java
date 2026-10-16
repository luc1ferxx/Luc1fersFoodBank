package com.laioffer.onlineorder.staff;


import com.fasterxml.jackson.annotation.JsonInclude;


import java.time.LocalDateTime;


/**
 * A merchant account as admins see it. Every field is always present, null included.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AdminMerchantDto(
        Long merchantId,
        Long customerId,
        String email,
        String firstName,
        String lastName,
        Long restaurantId,
        String restaurantName,
        boolean active,
        LocalDateTime createdAt
) {
}
