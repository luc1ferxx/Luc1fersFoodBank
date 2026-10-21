package com.laioffer.onlineorder.aftersales;


/**
 * Why the customer asks for a refund (after_sales_cases.reason_code; chk_after_sales_case_reason has the same list).
 */
public enum AfterSalesReason {
    MISSING_ITEM,
    WRONG_ITEM,
    QUALITY_ISSUE,
    DAMAGED_PACKAGING,
    FOOD_SAFETY,
    LATE_DELIVERY,
    OTHER
}
