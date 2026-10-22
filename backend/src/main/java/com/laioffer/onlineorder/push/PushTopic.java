package com.laioffer.onlineorder.push;


/**
 * The lists a push signal can say changed (docs/adr/0003-push-signals.md, "Topics and recipients"). A signal carries
 * only its topic: the client answers it by reading the REST endpoint that shows the list again.
 */
public enum PushTopic {

    /**
     * order_notifications of the customer ({@code customer:} recipients).
     */
    NOTIFICATIONS("notifications"),

    /**
     * merchant_orders of the restaurant ({@code restaurant:} recipients).
     */
    MERCHANT_ORDERS("merchant.orders"),

    /**
     * merchant_after_sales_cases of the restaurant ({@code restaurant:} recipients).
     */
    MERCHANT_AFTER_SALES("merchant.after_sales"),

    /**
     * The rider's deliveries ({@code rider:} recipients).
     */
    RIDER_DELIVERIES("rider.deliveries");


    private final String wireName;


    PushTopic(String wireName) {
        this.wireName = wireName;
    }


    /**
     * The name the stream and the Redis message carry.
     */
    public String wireName() {
        return wireName;
    }


    /**
     * @throws IllegalArgumentException for a name no topic has
     */
    public static PushTopic fromWireName(String wireName) {
        for (PushTopic topic : values()) {
            if (topic.wireName.equals(wireName)) {
                return topic;
            }
        }
        throw new IllegalArgumentException("Unknown push topic: " + wireName);
    }
}
