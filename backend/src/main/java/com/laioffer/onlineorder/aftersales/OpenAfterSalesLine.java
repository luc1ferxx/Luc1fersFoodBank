package com.laioffer.onlineorder.aftersales;


import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;


/**
 * @param orderItemId an order line ({@code items[].id} of the order)
 * @param quantity    how many of it, at most the quantity ordered
 */
public record OpenAfterSalesLine(
        @NotNull
        Long orderItemId,
        @NotNull
        @Min(1)
        Integer quantity
) {
}
