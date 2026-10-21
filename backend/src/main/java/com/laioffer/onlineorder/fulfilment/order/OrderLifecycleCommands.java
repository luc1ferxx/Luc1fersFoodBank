package com.laioffer.onlineorder.fulfilment.order;


import com.laioffer.onlineorder.messaging.AfterSalesCasePayload;
import com.laioffer.onlineorder.model.OrderDto;
import com.laioffer.onlineorder.model.OrderStatus;


import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;


/**
 * The order module's command API for the fulfilment modules: the only synchronous entry point into the module that
 * owns the order state machine and the after-sales cases of its orders. Merchant and timeout use it for their
 * commands; they never read or write the orders or after-sales tables or call the order module's other services.
 * Everything else between modules travels as events.
 *
 * <p>Every order command locks the order row (SELECT ... FOR UPDATE), decides its own trigger with
 * {@link com.laioffer.onlineorder.model.OrderStateMachine}, and writes the new status (with orders.updated_at, as a
 * compare-and-set on the status it read), its order_status_history row and the order event to the outbox in the
 * caller's transaction. A merchant accept and a timeout cancel therefore race on the same row lock and exactly one of
 * them wins; the loser sees the winner's status. A command that changes nothing (a repeat, or an order no longer in
 * the expected status) writes no history row. A command that cancels a PAID or ACCEPTED order also opens the order's
 * automatic refund in that transaction (ADR 0002 D12), so a failure there fails the command. The after-sales command
 * ({@link #decideAfterSalesCase}) does the same on the case row with the after-sales machine (ADR 0002 section 5.2).
 *
 * <p>Each command takes the {@link OrderActor} recorded in the history, and there are deliberately no overloads
 * without one: a new caller has to name its actor ({@link OrderActor#system()} for jobs such as the deadline
 * timeouts) instead of silently recording SYSTEM for a merchant's or a rider's action.
 *
 * <p>The rules the state machines enforce at run time, per command (the machine decides in this order: the actor,
 * whether the command already happened, the status it fires from, then its guards):
 * <table>
 *     <caption>Triggers, statuses and actors of each command</caption>
 *     <tr><th>Command</th><th>Trigger: from -&gt; to</th><th>Actors</th><th>Already done (changed = false)</th></tr>
 *     <tr><td>{@link #transitionRestaurantOrder}</td>
 *         <td>MERCHANT_ACCEPT: PAID -&gt; ACCEPTED; MERCHANT_REJECT: PAID -&gt; CANCELLED;
 *             MERCHANT_START_PREPARING: ACCEPTED -&gt; PREPARING</td>
 *         <td>MERCHANT, SYSTEM</td>
 *         <td>the order is in the target status (a reject: cancelled with the same reason code)</td></tr>
 *     <tr><td>{@link #cancelIfStillIn}</td>
 *         <td>PAYMENT_TIMEOUT: PLACED -&gt; CANCELLED; ACCEPT_TIMEOUT: PAID -&gt; CANCELLED</td>
 *         <td>SYSTEM</td>
 *         <td>never: from any other status nothing changes (empty)</td></tr>
 *     <tr><td>{@link #completeDeliveredOrder}</td>
 *         <td>DELIVERY_PREPARE: ACCEPTED -&gt; PREPARING; DELIVERY_COMPLETE: PREPARING -&gt; COMPLETED</td>
 *         <td>RIDER, SYSTEM</td>
 *         <td>never: each step fires only from its own status</td></tr>
 *     <tr><td>{@link #decideAfterSalesCase}</td>
 *         <td>MERCHANT_APPROVE: PENDING_MERCHANT -&gt; REFUNDING; MERCHANT_REJECT: PENDING_MERCHANT -&gt; REJECTED;
 *             MERCHANT_OFFER: PENDING_MERCHANT -&gt; COUNTER_OFFERED; each only before the case's deadline
 *             (guard beforeDeadline)</td>
 *         <td>MERCHANT</td>
 *         <td>the merchant gave this answer already (an offer: of the same amount), whatever status the case is in
 *             now</td></tr>
 * </table>
 * An actor the table does not allow is a programming error: {@link IllegalStateException}, after the row was locked,
 * and the transaction rolls back. A (required, target) pair of {@link #transitionRestaurantOrder} or an expected status
 * of {@link #cancelIfStillIn} that no trigger of the table has is an {@link IllegalArgumentException} before any lock.
 */
public interface OrderLifecycleCommands {

    String MERCHANT_REJECTED = "MERCHANT_REJECTED";
    String MERCHANT_TIMEOUT = "MERCHANT_TIMEOUT";
    String PAYMENT_TIMEOUT = "PAYMENT_TIMEOUT";
    String CUSTOMER_CANCELLED = "CUSTOMER_CANCELLED";
    String ADMIN_CANCELLED = "ADMIN_CANCELLED";

    String ORDER_STATUS_CONFLICT = "ORDER_STATUS_CONFLICT";

    /**
     * 409 of {@link #decideAfterSalesCase}: the case is not waiting for the merchant's answer (ADR 0002 section 11.1).
     */
    String AFTER_SALES_STATUS_CONFLICT = "AFTER_SALES_STATUS_CONFLICT";

    /**
     * 409 of {@link #decideAfterSalesCase}: the merchant's deadline on the case passed (ADR 0002 sections 6 and 19.1).
     */
    String AFTER_SALES_DEADLINE_PASSED = "AFTER_SALES_DEADLINE_PASSED";


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
     * Records that the rider picked the order up at {@code pickedUpAt} (delivery.picked_up), once: a redelivered or
     * replayed pickup leaves the first one. No status changes and no history row or event is written; a customer cancel
     * of the still ACCEPTED order afterwards refunds only part of it (ADR 0002 section 27, follow-up 17). The update
     * takes the order's row lock, so it and a cancel happen one after the other.
     *
     * @param actor the rider who picked it up ({@link OrderActor#rider(Long)}), or {@link OrderActor#system()}; any
     *              other actor is an {@link IllegalStateException}, like an actor a delivery trigger does not allow
     * @return whether this call recorded it: false for an unknown order or one whose pickup was already recorded
     */
    boolean recordPickup(Long orderId, LocalDateTime pickedUpAt, OrderActor actor);


    /**
     * The merchant's one answer to an after-sales request on an order of {@code restaurantId} (ADR 0002 sections 5.2,
     * 7 and 11.3): approve the requested amount, reject with a reason, or offer a partial refund. Locks the case row
     * (SELECT ... FOR UPDATE, scoped to the restaurant), decides MERCHANT_APPROVE, MERCHANT_REJECT or MERCHANT_OFFER
     * with the after-sales machine at the time read after that lock, and writes the step (the case with a
     * compare-and-set on the status and version it read, its history row with the actor, the refund when it enters
     * REFUNDING, and its after-sales event to the outbox) in the caller's transaction. A merchant answer and the
     * merchant timeout therefore race on the same row lock; from the deadline on the answer is refused even before the
     * poller fired the timeout. A repeat of the same answer (an offer: of the same amount) changes nothing and returns
     * {@link AfterSalesDecision#changed()} false, so clients can retry.
     *
     * @param actor the merchant, {@link OrderActor#merchant(Long)}: recorded in the case history and the event
     * @return the case after the command, as the data of its events
     * @throws com.laioffer.onlineorder.exception.ResourceNotFoundException 404 "After-sales case not found" when the
     *                                                                     case does not exist, belongs to another
     *                                                                     restaurant, or is the automatic refund of a
     *                                                                     cancelled order (no merchant answers it)
     * @throws com.laioffer.onlineorder.exception.ConflictException         {@link #AFTER_SALES_STATUS_CONFLICT} when
     *                                                                     the case does not wait for the merchant (or
     *                                                                     got another answer);
     *                                                                     {@link #AFTER_SALES_DEADLINE_PASSED} from its
     *                                                                     deadline on
     * @throws com.laioffer.onlineorder.exception.BadRequestException       a rejection without a reason, an offer
     *                                                                     without an amount, with more decimal places
     *                                                                     than the case's currency has, or not more
     *                                                                     than zero and less than the requested amount
     */
    AfterSalesDecision decideAfterSalesCase(
            Long caseId,
            Long restaurantId,
            AfterSalesResponse response,
            OrderActor actor
    );


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


    /**
     * The merchant's answer to an after-sales request.
     *
     * @param type        what the merchant answers
     * @param offerAmount the partial refund of an OFFER, in the case's currency; null for APPROVE and REJECT
     * @param note        the reason of a REJECT (required), an optional note of an OFFER or an APPROVE: kept on the
     *                    case (merchant_note) and in its history, trimmed
     */
    record AfterSalesResponse(Type type, BigDecimal offerAmount, String note) {

        public AfterSalesResponse {
            Objects.requireNonNull(type, "type");
        }


        public static AfterSalesResponse approve() {
            return new AfterSalesResponse(Type.APPROVE, null, null);
        }


        public static AfterSalesResponse reject(String reason) {
            return new AfterSalesResponse(Type.REJECT, null, reason);
        }


        public static AfterSalesResponse offer(BigDecimal amount, String note) {
            return new AfterSalesResponse(Type.OFFER, amount, note);
        }


        public enum Type {
            /**
             * Refund the requested amount.
             */
            APPROVE,
            /**
             * Refuse the request with a reason; the customer may ask the platform to step in.
             */
            REJECT,
            /**
             * Offer a partial refund, more than zero and less than the requested amount; the customer accepts or
             * declines it.
             */
            OFFER
        }
    }


    /**
     * @param snapshot the case after the command, in the shape of the data of its after-sales events: the merchant
     *                 module keeps its projection of the case from it the same way it does from the events. Its
     *                 action is the command's trigger and its actor the merchant, also when nothing changed
     * @param changed  true when this command answered the request; false when the merchant had given this answer
     *                 already (a repeated command), so callers count an answer only once
     */
    record AfterSalesDecision(AfterSalesCasePayload snapshot, boolean changed) {

        public AfterSalesDecision {
            Objects.requireNonNull(snapshot, "snapshot");
        }
    }
}
