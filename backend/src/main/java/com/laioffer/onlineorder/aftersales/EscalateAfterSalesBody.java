package com.laioffer.onlineorder.aftersales;


import jakarta.validation.constraints.Size;


/**
 * @param note what the customer tells the platform, kept in the case history; optional
 */
public record EscalateAfterSalesBody(
        @Size(max = 1000)
        String note
) {
}
