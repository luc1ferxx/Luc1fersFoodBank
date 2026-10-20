package com.laioffer.onlineorder.aftersales;


/**
 * Why a case was closed without a refund (after_sales_cases.close_reason); set exactly when the case is CLOSED.
 */
public enum AfterSalesCloseReason {
    /**
     * The customer withdrew the case; only such a case lets the customer request once more (ADR 0002 section 20).
     */
    WITHDRAWN,
    /**
     * The customer did not escalate a rejection in time.
     */
    CUSTOMER_TIMEOUT,
    /**
     * An admin rejected the request.
     */
    ARBITRATION_REJECTED
}
