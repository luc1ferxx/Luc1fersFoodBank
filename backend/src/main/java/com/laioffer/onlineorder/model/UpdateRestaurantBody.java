package com.laioffer.onlineorder.model;


import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;


/**
 * Partial update of a restaurant's basic fields: omitted (or null) fields keep their current value.
 */
public record UpdateRestaurantBody(
        @Size(max = 200)
        @Pattern(regexp = "(?s).*\\S.*", message = "must not be blank")
        String name,
        @Size(max = 500)
        String address,
        @Size(max = 50)
        String phone,
        @Size(max = 2000)
        String imageUrl
) {


    public boolean hasNoChanges() {
        return name == null && address == null && phone == null && imageUrl == null;
    }
}
