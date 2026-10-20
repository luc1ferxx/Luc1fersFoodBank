package com.laioffer.onlineorder.aftersales;


/**
 * Who fires an after-sales or refund trigger: the case's customer, the restaurant's merchant, an admin, or the system
 * (the deadline poller, the refund executor). The names are those of {@code OrderActor.Type}, so {@link #name()} is an
 * after_sales_case_history.actor_type.
 *
 * <p>The after-sales module's own type, like the dispatch module's {@code DeliveryActor}: {@code OrderActor} lives in
 * {@code fulfilment.order}, which is in a package cycle with {@code model} and {@code service} (ADR 0002 section 22),
 * and the order service calls into this package (cancellation refunds, merchant decisions). Declaring the machines
 * with {@code OrderActor.Type} would pull this package into that cycle.
 */
public enum AfterSalesActor {
    CUSTOMER,
    MERCHANT,
    ADMIN,
    SYSTEM
}
