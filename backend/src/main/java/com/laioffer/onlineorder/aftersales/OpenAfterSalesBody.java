package com.laioffer.onlineorder.aftersales;


import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;


import java.util.List;


/**
 * A customer's after-sales request (POST /orders/{orderId}/after-sales).
 *
 * @param scope       WHOLE_ORDER (every line in full; no lines) or ITEMS (the lines given)
 * @param lines       for ITEMS: the order lines and how many of each
 * @param reasonCode  MISSING_ITEM, WRONG_ITEM, QUALITY_ISSUE, DAMAGED_PACKAGING, FOOD_SAFETY, LATE_DELIVERY or OTHER
 * @param description what went wrong
 */
public record OpenAfterSalesBody(
        @NotBlank
        String scope,
        List<@NotNull @Valid OpenAfterSalesLine> lines,
        @NotBlank
        String reasonCode,
        @NotBlank
        @Size(max = 1000)
        String description
) {
}
