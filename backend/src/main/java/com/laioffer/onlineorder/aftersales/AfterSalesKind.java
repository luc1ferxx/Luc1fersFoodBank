package com.laioffer.onlineorder.aftersales;


/**
 * What a case is about (after_sales_cases.kind).
 */
public enum AfterSalesKind {
    /**
     * The customer asked for a refund of a completed order; opened in PENDING_MERCHANT, at most twice per order (the
     * second time only after withdrawing the first).
     */
    CUSTOMER_REQUEST,
    /**
     * The automatic refund of a paid order that was cancelled; opened in REFUNDING by the system.
     */
    ORDER_CANCELLED
}
