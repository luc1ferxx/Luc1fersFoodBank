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
 * {@link OrderStatus}, and writes the new status and the order event to the outbox in the caller's transaction. A
 * merchant accept and a timeout cancel therefore race on the same row lock and exactly one of them wins; the loser
 * sees the winner's status.
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
     * whose transition already happened (the order is in {@code targetStatus}) returns the order unchanged, so
     * clients can retry.
     *
     * @param cancelReason stored on the order when {@code targetStatus} is CANCELLED; ignored otherwise
     * @throws com.laioffer.onlineorder.exception.ResourceNotFoundException when the order does not exist or belongs
     *                                                                     to another restaurant (404, so a merchant
     *                                                                     cannot probe other restaurants' order ids)
     * @throws com.laioffer.onlineorder.exception.ConflictException         ORDER_STATUS_CONFLICT when the order is in
     *                                                                     any other status
     */
    OrderDto transitionRestaurantOrder(
            Long orderId,
            Long restaurantId,
            OrderStatus requiredStatus,
            OrderStatus targetStatus,
            String cancelReason
    );


    /**
     * Cancels the order only while it is still in {@code expectedStatus}: a timeout must not cancel an order that was
     * paid or accepted in the meantime, even though ACCEPTED -> CANCELLED is a valid transition.
     *
     * @return the cancelled order, or empty when the order does not exist or is no longer in {@code expectedStatus}
     * (nothing changed)
     */
    Optional<OrderDto> cancelIfStillIn(Long orderId, OrderStatus expectedStatus, String cancelReason);


    /**
     * Completes an order whose delivery was handed to the customer. From PREPARING the order moves to COMPLETED. From
     * ACCEPTED (the merchant never marked it PREPARING) it moves ACCEPTED -> PREPARING -> COMPLETED in this one
     * transaction, emitting order.preparing and order.completed: food that was delivered was prepared, and both are
     * valid guarded transitions. A COMPLETED order is left alone (duplicate delivery event); an order in any other
     * status (cancelled, or never accepted) is left alone and reported as {@link DeliveryCompletion#IGNORED}.
     */
    DeliveryCompletion completeDeliveredOrder(Long orderId);


    enum DeliveryCompletion {
        COMPLETED,
        ALREADY_COMPLETED,
        IGNORED,
        NOT_FOUND
    }
}
