package com.laioffer.onlineorder.model;


import java.util.Objects;


/**
 * Who caused an order status change, recorded in order_status_history with the change.
 *
 * @param type the kind of actor
 * @param id   the customers.id of a CUSTOMER, ADMIN or MERCHANT actor; the riders.id of a RIDER (the order module only
 *             learns the rider from the delivery event); null for SYSTEM (timeouts, migrations) and when unknown
 */
public record OrderActor(Type type, Long id) {

    private static final OrderActor SYSTEM = new OrderActor(Type.SYSTEM, null);


    public OrderActor {
        Objects.requireNonNull(type, "type");
        if (type == Type.SYSTEM && id != null) {
            throw new IllegalArgumentException("A SYSTEM actor has no id");
        }
    }


    public static OrderActor customer(Long customerId) {
        return new OrderActor(Type.CUSTOMER, customerId);
    }


    public static OrderActor admin(Long customerId) {
        return new OrderActor(Type.ADMIN, customerId);
    }


    public static OrderActor merchant(Long customerId) {
        return new OrderActor(Type.MERCHANT, customerId);
    }


    public static OrderActor rider(Long riderId) {
        return new OrderActor(Type.RIDER, riderId);
    }


    public static OrderActor system() {
        return SYSTEM;
    }


    public enum Type {
        CUSTOMER,
        ADMIN,
        MERCHANT,
        RIDER,
        SYSTEM
    }
}
