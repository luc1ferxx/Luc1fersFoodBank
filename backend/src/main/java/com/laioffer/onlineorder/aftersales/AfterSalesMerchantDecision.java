package com.laioffer.onlineorder.aftersales;


/**
 * The merchant's one answer to a request (after_sales_cases.merchant_decision); null until the merchant answered, and
 * for good when the merchant's deadline ran out first.
 */
public enum AfterSalesMerchantDecision {
    APPROVED,
    REJECTED,
    OFFERED
}
