package com.laioffer.onlineorder.aftersales;


/**
 * Who fires an after-sales or refund trigger: the case's customer, the restaurant's merchant, an admin, or the system
 * (the deadline poller, the refund executor). The names are those of {@code OrderActor.Type}, so {@link #name()} is an
 * after_sales_case_history.actor_type.
 *
 * <p>The after-sales module's own type, like the dispatch module's {@code DeliveryActor}: no rider takes an after-sales
 * step, so the machines' tables name only these four.
 */
public enum AfterSalesActor {
    CUSTOMER,
    MERCHANT,
    ADMIN,
    SYSTEM
}
