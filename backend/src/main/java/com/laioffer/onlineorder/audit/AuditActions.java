package com.laioffer.onlineorder.audit;


/**
 * The audited actions and their target types. Add new actions here, as {@code <target>.<verb>}.
 */
public final class AuditActions {

    public static final String ORDER_STATUS_UPDATE = "order.status.update";
    public static final String OUTBOX_EVENT_RETRY = "outbox_event.retry";
    public static final String OUTBOX_EVENT_ABANDON = "outbox_event.abandon";
    public static final String DEAD_LETTER_REPLAY = "dead_letter.replay";
    public static final String MENU_ITEM_UPDATE = "menu_item.update";
    public static final String RESTAURANT_UPDATE = "restaurant.update";
    public static final String DELIVERY_PICKED_UP = "delivery.picked_up";
    public static final String DELIVERY_DELIVERED = "delivery.delivered";
    public static final String DELIVERY_REASSIGN = "delivery.reassign";
    public static final String MERCHANT_CREATE = "merchant.create";
    public static final String MERCHANT_UPDATE = "merchant.update";
    public static final String RIDER_CREATE = "rider.create";
    public static final String RIDER_UPDATE = "rider.update";
    public static final String RIDER_AVAILABILITY = "rider.availability";
    public static final String AFTER_SALES_CASE_ARBITRATE = "after_sales_case.arbitrate";
    public static final String REFUND_RETRY = "refund.retry";

    public static final String TARGET_ORDER = "ORDER";
    public static final String TARGET_OUTBOX_EVENT = "OUTBOX_EVENT";
    public static final String TARGET_DEAD_LETTER_EVENT = "DEAD_LETTER_EVENT";
    public static final String TARGET_MENU_ITEM = "MENU_ITEM";
    public static final String TARGET_RESTAURANT = "RESTAURANT";
    public static final String TARGET_DELIVERY = "DELIVERY";
    public static final String TARGET_MERCHANT = "MERCHANT";
    public static final String TARGET_RIDER = "RIDER";
    public static final String TARGET_AFTER_SALES_CASE = "AFTER_SALES_CASE";
    public static final String TARGET_REFUND = "REFUND";


    private AuditActions() {
    }
}
