package com.laioffer.onlineorder.model;


import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;


import java.math.BigDecimal;


/**
 * Partial update of a menu item: omitted (or null) fields keep their current value.
 *
 * @param price    the new price, in the currency of the item's restaurant
 * @param currency optional, only together with {@code price}: the currency the price is in. It must be the
 *                 restaurant's currency (a restaurant prices in one currency); omitted, the price is taken to be in it.
 */
public record UpdateMenuItemBody(
        @Size(max = 200)
        @Pattern(regexp = "(?s).*\\S.*", message = "must not be blank")
        String name,
        @Size(max = 2000)
        String description,
        @DecimalMin("0.00")
        @Digits(integer = 8, fraction = 2)
        BigDecimal price,
        @Pattern(regexp = "[A-Z]{3}", message = "must be an ISO 4217 code of three upper-case letters")
        String currency
) {


    /**
     * A change of name, description and price, without a currency.
     */
    public UpdateMenuItemBody(String name, String description, BigDecimal price) {
        this(name, description, price, null);
    }


    public boolean hasNoChanges() {
        return name == null && description == null && price == null;
    }
}
