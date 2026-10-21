package com.laioffer.onlineorder.aftersales;


import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;


import java.math.BigDecimal;


/**
 * An admin's decision on an escalated case (POST /admin/after-sales/{caseId}/arbitrate).
 *
 * @param decision FULL (refund the requested amount), PARTIAL (refund {@code amount}) or REJECT (close the case
 *                 without a refund)
 * @param amount   PARTIAL only: more than zero and less than the requested amount, in the case's currency; FULL and
 *                 REJECT take none
 * @param note     why, kept on the case (admin_note) and in its history; shown to the customer
 */
public record ArbitrateAfterSalesBody(
        @NotBlank
        String decision,
        BigDecimal amount,
        @NotBlank
        @Size(max = 1000)
        String note
) {
}
