package com.laioffer.onlineorder.fulfilment;


/**
 * Event types the fulfilment modules publish and consume. Order event types are derived from the order status
 * ({@link com.laioffer.onlineorder.model.OrderStatus#eventType()}); delivery events are published by the dispatch
 * module on the delivery topic, merchant events by the merchant module on the merchant topic. After-sales events are
 * published by the after-sales module on the after-sales topic, one type per status a case enters
 * ({@link com.laioffer.onlineorder.aftersales.AfterSalesStatus#eventType()}); opening a case is always case_opened,
 * whatever status it opens in.
 */
public final class FulfilmentEvents {

    public static final String ORDER_CREATED = "order.created";
    public static final String ORDER_PAID = "order.paid";
    public static final String ORDER_ACCEPTED = "order.accepted";
    public static final String ORDER_PREPARING = "order.preparing";
    public static final String ORDER_COMPLETED = "order.completed";
    public static final String ORDER_CANCELLED = "order.cancelled";

    public static final String DELIVERY_ASSIGNED = "delivery.assigned";
    public static final String DELIVERY_PICKED_UP = "delivery.picked_up";
    public static final String DELIVERY_DELIVERED = "delivery.delivered";
    public static final String DELIVERY_CANCELLED = "delivery.cancelled";
    /**
     * An admin moved an open delivery to another rider (POST /admin/deliveries/{id}/reassign); the status is unchanged.
     * The order and notification consumers ignore it.
     */
    public static final String DELIVERY_REASSIGNED = "delivery.reassigned";

    /**
     * A paid order reached the inbox of a restaurant with at least one active merchant account, so a merchant is
     * expected to accept it: the timeout module sets its accept deadline. Not published for unstaffed restaurants.
     */
    public static final String MERCHANT_ORDER_RECEIVED = "merchant.order_received";

    /**
     * A case was opened: a customer's request (PENDING_MERCHANT) or the automatic refund of a cancelled paid order
     * (REFUNDING).
     */
    public static final String AFTER_SALES_CASE_OPENED = "aftersales.case_opened";
    /**
     * The merchant offered a partial refund (COUNTER_OFFERED).
     */
    public static final String AFTER_SALES_OFFER_MADE = "aftersales.offer_made";
    /**
     * The merchant rejected the request or the customer declined the offer (REJECTED).
     */
    public static final String AFTER_SALES_REQUEST_REJECTED = "aftersales.request_rejected";
    /**
     * The customer asked the platform to step in (IN_ARBITRATION).
     */
    public static final String AFTER_SALES_CASE_ESCALATED = "aftersales.case_escalated";
    /**
     * The amount to refund is settled and the refund started (REFUNDING).
     */
    public static final String AFTER_SALES_REFUND_APPROVED = "aftersales.refund_approved";
    /**
     * The approved amount was refunded (REFUNDED).
     */
    public static final String AFTER_SALES_CASE_REFUNDED = "aftersales.case_refunded";
    /**
     * The case was closed without a refund (CLOSED).
     */
    public static final String AFTER_SALES_CASE_CLOSED = "aftersales.case_closed";


    private FulfilmentEvents() {
    }
}
