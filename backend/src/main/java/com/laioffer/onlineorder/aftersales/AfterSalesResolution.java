package com.laioffer.onlineorder.aftersales;


/**
 * Why a case is refunded and how its approved amount was set (after_sales_cases.resolution), written when the case
 * enters REFUNDING.
 */
public enum AfterSalesResolution {
    /**
     * The merchant approved the requested amount.
     */
    MERCHANT_APPROVED,
    /**
     * The merchant did not answer in time: the requested amount.
     */
    MERCHANT_TIMEOUT,
    /**
     * The customer accepted the offered amount.
     */
    OFFER_ACCEPTED,
    /**
     * The customer did not answer the offer in time: the offered amount.
     */
    OFFER_TIMEOUT,
    /**
     * An admin granted the requested amount.
     */
    ARBITRATION_FULL,
    /**
     * An admin granted part of it.
     */
    ARBITRATION_PARTIAL,
    /**
     * No admin decided in time: the requested amount.
     */
    ARBITRATION_TIMEOUT,
    /**
     * The automatic refund of a paid order that was cancelled: the order total.
     */
    ORDER_CANCELLED
}
