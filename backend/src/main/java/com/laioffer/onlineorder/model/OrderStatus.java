package com.laioffer.onlineorder.model;


import com.laioffer.onlineorder.exception.BadRequestException;
import com.laioffer.onlineorder.exception.ConflictException;


import java.util.EnumSet;
import java.util.Map;
import java.util.Set;


public enum OrderStatus {
    PLACED,
    PAID,
    ACCEPTED,
    PREPARING,
    COMPLETED,
    CANCELLED;

    private static final Map<OrderStatus, Set<OrderStatus>> TRANSITIONS = Map.of(
            PLACED, EnumSet.of(PAID, CANCELLED),
            PAID, EnumSet.of(ACCEPTED, CANCELLED),
            ACCEPTED, EnumSet.of(PREPARING, CANCELLED),
            PREPARING, EnumSet.of(COMPLETED),
            COMPLETED, EnumSet.noneOf(OrderStatus.class),
            CANCELLED, EnumSet.noneOf(OrderStatus.class)
    );

    private static final Set<OrderStatus> CHECKOUT_INITIAL_STATES = EnumSet.of(PLACED, PAID);


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


    public boolean canTransitionTo(OrderStatus target) {
        return TRANSITIONS.getOrDefault(this, Set.of()).contains(target);
    }


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
