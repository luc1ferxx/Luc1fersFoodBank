package com.laioffer.onlineorder.fulfilment.merchant;


import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;


import java.math.BigDecimal;


/**
 * POST /merchant/after-sales/{caseId}/offer: a partial refund, in the case's currency, more than zero and less than
 * the requested amount, with an optional note for the customer.
 */
public record OfferAfterSalesBody(
        @NotNull
        BigDecimal amount,
        @Size(max = 500)
        String note
) {
}
