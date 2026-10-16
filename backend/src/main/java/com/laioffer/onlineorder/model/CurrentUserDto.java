package com.laioffer.onlineorder.model;


import com.fasterxml.jackson.annotation.JsonInclude;
import com.laioffer.onlineorder.entity.CustomerEntity;


import java.util.List;


/**
 * GET /me.
 *
 * @param roles    the session's authorities, for example ROLE_USER, ROLE_ADMIN, ROLE_MERCHANT, ROLE_RIDER
 * @param merchant the merchant account bound to this login, or null (always present in the JSON)
 * @param rider    the rider bound to this login, or null (always present in the JSON)
 */
public record CurrentUserDto(
        Long id,
        String email,
        String firstName,
        String lastName,
        List<String> roles,
        @JsonInclude(JsonInclude.Include.ALWAYS)
        Merchant merchant,
        @JsonInclude(JsonInclude.Include.ALWAYS)
        Rider rider
) {

    public CurrentUserDto(CustomerEntity customer, List<String> roles, Merchant merchant, Rider rider) {
        this(customer.id(), customer.email(), customer.firstName(), customer.lastName(), roles, merchant, rider);
    }


    /**
     * @param active false once an admin deactivated the account: merchant endpoints then answer 403
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Merchant(
            Long merchantId,
            Long restaurantId,
            String restaurantName,
            boolean active
    ) {
    }


    /**
     * @param active false once an admin deactivated the rider: rider endpoints then answer 403
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Rider(
            Long riderId,
            String name,
            boolean onDuty,
            boolean active
    ) {
    }
}
