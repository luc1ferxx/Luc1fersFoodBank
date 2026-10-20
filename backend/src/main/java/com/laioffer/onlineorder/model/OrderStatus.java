package com.laioffer.onlineorder.model;


import com.laioffer.onlineorder.exception.BadRequestException;
import com.laioffer.onlineorder.exception.ConflictException;


import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;


/**
 * PLACED -> PAID -> ACCEPTED -> PREPARING -> COMPLETED; PLACED, PAID or ACCEPTED -> CANCELLED. The transitions, with
 * who may make them, are declared in {@link OrderStateMachine}.
 */
public enum OrderStatus {
    PLACED,
    PAID,
    ACCEPTED,
    PREPARING,
    COMPLETED,
    CANCELLED;

    /**
     * The statuses checkout creates an order in. Checkout inserts the order itself, outside {@link OrderStateMachine};
     * these are the machine's initial states (OrderStateMachineTests).
     */
    static final Set<OrderStatus> CHECKOUT_INITIAL_STATES = Collections.unmodifiableSet(EnumSet.of(PLACED, PAID));


    public static OrderStatus normalize(String value) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException("Order status is required");
        }
        try {
            return OrderStatus.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("Unsupported order status: " + value);
        }
    }


    public static OrderStatus normalizeForCheckout(String value) {
        OrderStatus status = value == null || value.isBlank() ? PLACED : normalize(value);
        if (!CHECKOUT_INITIAL_STATES.contains(status)) {
            throw new BadRequestException("Checkout can only create PLACED or PAID orders");
        }
        return status;
    }


    /**
     * True when some order command moves this status to {@code target}; staying in the same status is not a move.
     */
    public boolean canTransitionTo(OrderStatus target) {
        // Read lazily: this enum's initialization must not depend on the machine, which is declared over it.
        return OrderStateMachine.DEFINITION.canMove(this, target);
    }


    /**
     * Accepts the same status and every move {@link #canTransitionTo} allows.
     *
     * @throws ConflictException "Invalid order status transition: X -> Y" for any other pair
     */
    public void validateTransitionTo(OrderStatus target) {
        if (this == target) {
            return;
        }
        if (!canTransitionTo(target)) {
            throw new ConflictException("Invalid order status transition: " + this + " -> " + target);
        }
    }


    public String eventType() {
        return switch (this) {
            case PLACED -> "order.created";
            case PAID -> "order.paid";
            case ACCEPTED -> "order.accepted";
            case PREPARING -> "order.preparing";
            case COMPLETED -> "order.completed";
            case CANCELLED -> "order.cancelled";
        };
    }
}
