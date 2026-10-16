package com.laioffer.onlineorder.fulfilment.order;


import com.laioffer.onlineorder.model.OrderDto;
import com.laioffer.onlineorder.model.OrderStatus;


import java.util.Optional;


/**
 * The order module's command API for the fulfilment modules: the only synchronous entry point into the module that
 * owns the order state machine. Merchant and timeout use it for their commands; they never read or write the
 * orders table or call the order module's other services. Everything else between modules travels as events.
 *
 * <p>Every command locks the order row (SELECT ... FOR UPDATE), checks the transition against
 * {@link OrderStatus}, and writes the new status (with orders.updated_at), its order_status_history row and the order
 * event to the outbox in the caller's transaction. A merchant accept and a timeout cancel therefore race on the same
 * row lock and exactly one of them wins; the loser sees the winner's status. A command that changes nothing (a
 * repeat, or an order no longer in the expected status) writes no history row.
 *
 * <p>Each command takes the {@link OrderActor} recorded in the order history, and there are deliberately no overloads
 * without one: a new caller has to name its actor ({@link OrderActor#system()} for jobs such as the deadline
 * timeouts) instead of silently recording SYSTEM for a merchant's or a rider's action.
 */
public interface OrderLifecycleCommands {

    String MERCHANT_REJECTED = "MERCHANT_REJECTED";
    String MERCHANT_TIMEOUT = "MERCHANT_TIMEOUT";
    String PAYMENT_TIMEOUT = "PAYMENT_TIMEOUT";
    String CUSTOMER_CANCELLED = "CUSTOMER_CANCELLED";
    String ADMIN_CANCELLED = "ADMIN_CANCELLED";

    String ORDER_STATUS_CONFLICT = "ORDER_STATUS_CONFLICT";


    /**
     * Moves an order of {@code restaurantId} from {@code requiredStatus} to {@code targetStatus}. Repeating a command
     * whose transition already happened returns the order unchanged with {@link RestaurantOrderTransition#changed()}
     * false, so clients can retry. For a cancel, the order only counts as already cancelled by this command when its
     * cancel reason has the same code (the part before the first ':', for example MERCHANT_REJECTED): a reject of an
     * order the customer or the accept timeout already cancelled is a conflict, not a successful reject.
     *
     * @param cancelReason stored on the order (and as the history reason) when {@code targetStatus} is CANCELLED;
     *                     ignored otherwise
     * @param actor        recorded in the order history, for example {@link OrderActor#merchant(Long)}
     * @throws com.laioffer.onlineorder.exception.ResourceNotFoundException when the order does not exist or belongs
     *                                                                     to another restaurant (404, so a merchant
     *                                                                     cannot probe other restaurants' order ids)
     * @throws com.laioffer.onlineorder.exception.ConflictException         ORDER_STATUS_CONFLICT when the order is in
     *                                                                     any other status, or was cancelled for
     *                                                                     another reason
     */
    RestaurantOrderTransition transitionRestaurantOrder(
            Long orderId,
            Long restaurantId,
            OrderStatus requiredStatus,
            OrderStatus targetStatus,
            String cancelReason,
            OrderActor actor
    );


    /**
     * Cancels the order only while it is still in {@code expectedStatus}: a timeout must not cancel an order that was
     * paid or accepted in the meantime, even though ACCEPTED -> CANCELLED is a valid transition.
     *
     * @param actor recorded in the order history; {@link OrderActor#system()} for the deadline timeouts
     * @return the cancelled order, or empty when the order does not exist or is no longer in {@code expectedStatus}
     * (nothing changed)
     */
    Optional<OrderDto> cancelIfStillIn(Long orderId, OrderStatus expectedStatus, String cancelReason, OrderActor actor);


    /**
     * Completes an order whose delivery was handed to the customer. From PREPARING the order moves to COMPLETED. From
     * ACCEPTED (the merchant never marked it PREPARING) it moves ACCEPTED -> PREPARING -> COMPLETED in this one
     * transaction, emitting order.preparing and order.completed and recording both steps in the order history: food
     * that was delivered was prepared, and both are valid guarded transitions. A COMPLETED order is left alone
     * (duplicate delivery event); an order in any other status (cancelled, or never accepted) is left alone and
     * reported as {@link DeliveryCompletion#IGNORED}.
     *
     * @param actor recorded for each step, normally {@link OrderActor#rider(Long)} of the delivering rider
     */
    DeliveryCompletion completeDeliveredOrder(Long orderId, OrderActor actor);


    /**
     * @param order   the order after the command
     * @param changed true when this command made the transition; false when it had already happened (a repeated
     *                command), so callers count a decision only once
     */
    record RestaurantOrderTransition(OrderDto order, boolean changed) {
    }


    enum DeliveryCompletion {
        COMPLETED,
        ALREADY_COMPLETED,
        IGNORED,
        NOT_FOUND
    }
}
