package com.laioffer.onlineorder.fulfilment.merchant;


import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;


/**
 * POST /merchant/after-sales/{caseId}/reject: the reason the customer sees.
 */
public record RejectAfterSalesBody(
        @NotBlank
        @Size(max = 500)
        String reason
) {
}
