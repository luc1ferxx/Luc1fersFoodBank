package com.laioffer.onlineorder.model;


import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;


import java.math.BigDecimal;


/**
 * Partial update of a menu item: omitted (or null) fields keep their current value.
 */
public record UpdateMenuItemBody(
        @Size(max = 200)
        @Pattern(regexp = "(?s).*\\S.*", message = "must not be blank")
        String name,
        @Size(max = 2000)
        String description,
        @DecimalMin("0.00")
        @Digits(integer = 8, fraction = 2)
        BigDecimal price
) {


    public boolean hasNoChanges() {
        return name == null && description == null && price == null;
    }
}
