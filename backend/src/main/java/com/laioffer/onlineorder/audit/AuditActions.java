package com.laioffer.onlineorder.audit;


/**
 * The audited actions and their target types. Add new actions here, as {@code <target>.<verb>}.
 */
public final class AuditActions {

    public static final String ORDER_STATUS_UPDATE = "order.status.update";
    public static final String OUTBOX_EVENT_RETRY = "outbox_event.retry";
    public static final String DEAD_LETTER_REPLAY = "dead_letter.replay";
    public static final String MENU_ITEM_UPDATE = "menu_item.update";
    public static final String RESTAURANT_UPDATE = "restaurant.update";

    public static final String TARGET_ORDER = "ORDER";
    public static final String TARGET_OUTBOX_EVENT = "OUTBOX_EVENT";
    public static final String TARGET_DEAD_LETTER_EVENT = "DEAD_LETTER_EVENT";
    public static final String TARGET_MENU_ITEM = "MENU_ITEM";
    public static final String TARGET_RESTAURANT = "RESTAURANT";


    private AuditActions() {
    }
}
