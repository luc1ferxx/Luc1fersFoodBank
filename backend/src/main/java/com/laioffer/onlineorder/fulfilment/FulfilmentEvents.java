package com.laioffer.onlineorder.fulfilment;


/**
 * Event types the fulfilment modules publish and consume. Order event types are derived from the order status
 * ({@link com.laioffer.onlineorder.model.OrderStatus#eventType()}); delivery events are published by the dispatch
 * module on the delivery topic, merchant events by the merchant module on the merchant topic.
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
     * A paid order reached the inbox of a restaurant with at least one active merchant account, so a merchant is
     * expected to accept it: the timeout module sets its accept deadline. Not published for unstaffed restaurants.
     */
    public static final String MERCHANT_ORDER_RECEIVED = "merchant.order_received";


    private FulfilmentEvents() {
    }
}
