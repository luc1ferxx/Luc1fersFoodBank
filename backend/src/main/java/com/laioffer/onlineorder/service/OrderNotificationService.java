package com.laioffer.onlineorder.service;


import com.laioffer.onlineorder.entity.OrderEntity;
import com.laioffer.onlineorder.entity.OrderNotificationEntity;
import com.laioffer.onlineorder.fulfilment.FulfilmentEvents;
import com.laioffer.onlineorder.fulfilment.order.OrderLifecycleCommands;
import com.laioffer.onlineorder.messaging.AfterSalesCasePayload;
import com.laioffer.onlineorder.messaging.AfterSalesEventEnvelope;
import com.laioffer.onlineorder.messaging.DeliveryEventEnvelope;
import com.laioffer.onlineorder.messaging.DeliveryEventPayload;
import com.laioffer.onlineorder.messaging.OrderEventEnvelope;
import com.laioffer.onlineorder.messaging.OrderEventPayload;
import com.laioffer.onlineorder.model.OrderNotificationDto;
import com.laioffer.onlineorder.money.Money;
import com.laioffer.onlineorder.push.PushRecipient;
import com.laioffer.onlineorder.push.PushSignals;
import com.laioffer.onlineorder.push.PushTopic;
import com.laioffer.onlineorder.repository.OrderNotificationRepository;
import com.laioffer.onlineorder.repository.OrderPaymentRepository;
import com.laioffer.onlineorder.repository.OrderRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;


@Service
public class OrderNotificationService {

    /**
     * For a paid order without a captured payment to refund (ADR 0002 D16): its payment is refunded by hand.
     */
    private static final String PAYMENT_SUPPORT_NOTE = "Please contact support about your payment.";

    private static final String AUTOMATIC_REFUND_NOTE = "Your payment will be refunded automatically.";

    /**
     * The dedup_scope prefix of the after-sales notifications ({@link #afterSalesScope}).
     */
    private static final String AFTER_SALES_SCOPE_PREFIX = "case:";

    /**
     * The values of the after-sales events this service tells apart (the names of the after-sales module's kind,
     * trigger, resolution and close-reason enums, as the event contract writes them). Spelled out here because the order
     * module does not depend on the after-sales module, which depends on it.
     */
    private static final String ORDER_CANCELLED_KIND = "ORDER_CANCELLED";

    private static final String CUSTOMER_DECLINE_OFFER_ACTION = "CUSTOMER_DECLINE_OFFER";

    /**
     * The after-sales event types with a notification of a customer request.
     */
    private static final Set<String> DELIVERY_NOTICE_TYPES = Set.of(
            FulfilmentEvents.DELIVERY_ASSIGNED,
            FulfilmentEvents.DELIVERY_PICKED_UP,
            FulfilmentEvents.DELIVERY_DELIVERED
    );

    private static final Set<String> AFTER_SALES_NOTICE_TYPES = Set.of(
            FulfilmentEvents.AFTER_SALES_CASE_OPENED,
            FulfilmentEvents.AFTER_SALES_OFFER_MADE,
            FulfilmentEvents.AFTER_SALES_REQUEST_REJECTED,
            FulfilmentEvents.AFTER_SALES_CASE_ESCALATED,
            FulfilmentEvents.AFTER_SALES_REFUND_APPROVED,
            FulfilmentEvents.AFTER_SALES_CASE_REFUNDED,
            FulfilmentEvents.AFTER_SALES_CASE_CLOSED
    );

    private static final Map<String, String> REFUND_APPROVED_LEADS = Map.of(
            "MERCHANT_APPROVED", "The restaurant approved your refund request: ",
            "MERCHANT_TIMEOUT", "The restaurant did not answer in time, so your refund request was approved: ",
            "OFFER_ACCEPTED", "You accepted the restaurant's offer: ",
            "OFFER_TIMEOUT", "You did not answer the restaurant's offer in time, so it was accepted: ",
            "ARBITRATION_FULL", "The platform approved your refund request: ",
            "ARBITRATION_PARTIAL", "The platform approved part of your refund request: ",
            "ARBITRATION_TIMEOUT", "The platform did not decide in time, so your refund request was approved: "
    );

    private static final String WITHDRAWN = "WITHDRAWN";

    private static final String CUSTOMER_TIMEOUT = "CUSTOMER_TIMEOUT";

    private static final String ARBITRATION_REJECTED = "ARBITRATION_REJECTED";

    /**
     * A deadline in a message, to the minute and with its zone: "2026-10-21 12:00 UTC". Truncated, so the customer never
     * reads a later time than the real deadline. The server runs in UTC, and the UI shows a message as it comes while it
     * shows every other time in the browser's zone, so the zone is named.
     */
    private static final DateTimeFormatter DEADLINE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'");

    private final OrderNotificationRepository orderNotificationRepository;
    private final OrderRepository orderRepository;
    private final OrderPaymentRepository orderPaymentRepository;
    private final PushSignals pushSignals;

    /**
     * app.after-sales.picked-up-cancel-refund-percent as a factor (the after-sales module checks its range at startup).
     */
    private final BigDecimal pickedUpRefundShare;


    public OrderNotificationService(
            OrderNotificationRepository orderNotificationRepository,
            OrderRepository orderRepository,
            OrderPaymentRepository orderPaymentRepository,
            PushSignals pushSignals,
            @Value("${app.after-sales.picked-up-cancel-refund-percent:50}") int pickedUpRefundPercent
    ) {
        this.orderNotificationRepository = orderNotificationRepository;
        this.orderRepository = orderRepository;
        this.orderPaymentRepository = orderPaymentRepository;
        this.pushSignals = pushSignals;
        this.pickedUpRefundShare = BigDecimal.valueOf(pickedUpRefundPercent).movePointLeft(2);
    }


    /**
     * Messages name the order by its public order number (orders.order_no), the number the customer sees; the
     * notification row keeps the internal order id.
     */
    public void recordOrderEvent(OrderEventEnvelope event) {
        validate(event);
        OrderEventPayload data = event.data();
        int inserted = orderNotificationRepository.insertIfAbsent(
                event.aggregateId(),
                data.customerId(),
                event.eventType(),
                buildTitle(event),
                buildMessage(event, orderNumber(event.aggregateId()), event.aggregateId()),
                LocalDateTime.now()
        );
        signalIfInserted(inserted, data.customerId());
    }


    /**
     * Tells the customer about their delivery: rider assigned, picked up, delivered. A cancelled delivery gets no
     * notification of its own; the order's cancellation notification covers it. One notification per order and
     * event type (uq_notification_order_event_scope, with the empty scope), so a redelivered event adds nothing.
     */
    public void recordDeliveryEvent(DeliveryEventEnvelope event) {
        if (event == null || event.data() == null || event.data().orderId() == null || event.data().customerId() == null) {
            throw new IllegalArgumentException("Delivery event payload is missing required identifiers");
        }
        DeliveryEventPayload data = event.data();
        String orderNumber = orderNumber(data.orderId());
        String riderName = data.riderName() == null || data.riderName().isBlank() ? "Your rider" : data.riderName();
        String title;
        String message;
        switch (event.eventType() == null ? "" : event.eventType()) {
            case "delivery.assigned" -> {
                title = "Rider assigned";
                message = riderName + " will deliver order #" + orderNumber + ".";
            }
            case "delivery.picked_up" -> {
                title = "Order on the way";
                message = riderName + " picked up order #" + orderNumber + " and is on the way.";
            }
            case "delivery.delivered" -> {
                title = "Order delivered";
                message = "Order #" + orderNumber + " was delivered. Enjoy your meal!";
            }
            default -> {
                return;
            }
        }
        int inserted = orderNotificationRepository.insertIfAbsent(
                data.orderId(),
                data.customerId(),
                event.eventType(),
                title,
                message,
                LocalDateTime.now()
        );
        signalIfInserted(inserted, data.customerId());
    }


    /**
     * Whether {@link #recordDeliveryEvent} notifies the customer of {@code event}: the rider was assigned, picked the
     * order up or delivered it. The delivery notification consumer moves its position in the delivery's events only for
     * these.
     */
    public boolean notifiesOf(DeliveryEventEnvelope event) {
        return event != null && DELIVERY_NOTICE_TYPES.contains(event.eventType());
    }


    /**
     * The dedup_scope of the notifications of an after-sales case: an order can have two customer requests (the second
     * after withdrawing the first, ADR 0002 section 20) whose events have the same types, so each case keeps its own
     * notification per event type. Order and delivery notifications keep the empty scope.
     */
    public static String afterSalesScope(Long caseId) {
        return AFTER_SALES_SCOPE_PREFIX + caseId;
    }


    /**
     * Tells the customer about a step of their after-sales case (ADR 0002 section 10.3): the request sent to the
     * restaurant, its counter-offer, a rejection (with the deadline to ask the platform to step in), the escalation to
     * the platform, the approved refund, the completed refund and the closing, by its reason. The automatic refund of
     * a cancelled order (kind ORDER_CANCELLED) only gets the completed refund: the cancellation notice already said the
     * payment will be refunded. Amounts carry their currency; the order is named by its public number. One
     * notification per order, event type and case ({@link #afterSalesScope}), so a redelivered event adds nothing,
     * while the second request of an order gets its own.
     */
    public void recordAfterSalesEvent(AfterSalesEventEnvelope event) {
        if (event == null || event.data() == null || event.data().caseId() == null || event.data().orderId() == null
                || event.data().customerId() == null) {
            throw new IllegalArgumentException("After-sales event payload is missing required identifiers");
        }
        AfterSalesCasePayload data = event.data();
        String eventType = event.eventType();
        if (!notifiesOf(event)) {
            return;
        }
        String orderNumber = data.orderNo() == null ? orderNumber(data.orderId()) : String.valueOf(data.orderNo());
        Optional<Notice> notice = afterSalesNotice(eventType, data, orderNumber);
        if (notice.isEmpty()) {
            return;
        }
        int inserted = orderNotificationRepository.insertIfAbsentInScope(
                data.orderId(),
                data.customerId(),
                eventType,
                afterSalesScope(data.caseId()),
                notice.get().title(),
                notice.get().message(),
                LocalDateTime.now()
        );
        signalIfInserted(inserted, data.customerId());
    }


    /**
     * Whether {@link #recordAfterSalesEvent} notifies the customer of {@code event}: every step of a customer request,
     * and only the completed refund of a cancelled order's case. The after-sales notification consumer moves its
     * position in the case's events only for these.
     */
    public boolean notifiesOf(AfterSalesEventEnvelope event) {
        if (event == null || event.data() == null || event.eventType() == null) {
            return false;
        }
        if (ORDER_CANCELLED_KIND.equals(event.data().kind())) {
            return FulfilmentEvents.AFTER_SALES_CASE_REFUNDED.equals(event.eventType());
        }
        return AFTER_SALES_NOTICE_TYPES.contains(event.eventType());
    }


    @Transactional(readOnly = true)
    public List<OrderNotificationDto> getNotificationsByCustomerId(Long customerId) {
        return getNotificationsByCustomerId(customerId, 50);
    }


    /**
     * The customer's newest notifications (GET /notifications). Read-only, so a replica read when replicas are
     * configured: the consumers write notifications asynchronously anyway.
     */
    @Transactional(readOnly = true)
    public List<OrderNotificationDto> getNotificationsByCustomerId(Long customerId, int limit) {
        List<OrderNotificationEntity> page =
                orderNotificationRepository.findPageByCustomerIdOrderByCreatedAtDesc(customerId, normalizeLimit(limit));
        Map<Long, Long> orderNumbers = new HashMap<>();
        List<Long> orderIds = page.stream().map(OrderNotificationEntity::orderId).distinct().toList();
        if (!orderIds.isEmpty()) {
            for (OrderEntity order : orderRepository.findAllById(orderIds)) {
                orderNumbers.put(order.id(), order.orderNo());
            }
        }
        List<OrderNotificationDto> results = new ArrayList<>();
        for (OrderNotificationEntity entity : page) {
            results.add(new OrderNotificationDto(entity, orderNumbers.get(entity.orderId())));
        }
        return results;
    }


    /**
     * Tells the customer's open streams that their notifications changed (push topic {@code notifications}), after
     * the commit and only when this call inserted one: a redelivered event adds nothing and signals nothing.
     */
    private void signalIfInserted(int inserted, Long customerId) {
        if (inserted > 0) {
            pushSignals.publish(PushRecipient.customer(customerId), PushTopic.NOTIFICATIONS);
        }
    }


    /**
     * The order number to show for {@code orderId}, or its internal id when no order number is found.
     */
    private String orderNumber(Long orderId) {
        return String.valueOf(orderRepository.findOrderNoById(orderId).orElse(orderId));
    }


    private int normalizeLimit(int limit) {
        return Math.max(1, Math.min(limit, 100));
    }


    private void validate(OrderEventEnvelope event) {
        if (event == null || event.aggregateId() == null || event.data() == null || event.data().customerId() == null) {
            throw new IllegalArgumentException("Order event payload is missing required identifiers");
        }
        if (event.eventType() == null || event.eventType().isBlank()) {
            throw new IllegalArgumentException("Order event type is required");
        }
    }


    private String buildTitle(OrderEventEnvelope event) {
        return switch (event.eventType()) {
            case "order.paid" -> "Payment confirmed";
            case "order.created" -> "Order placed";
            case "order.accepted" -> "Order accepted";
            case "order.preparing" -> "Preparing your order";
            case "order.completed" -> "Order completed";
            case "order.cancelled" -> buildCancelledTitle(event.data().reason());
            default -> "Order update";
        };
    }


    private String buildCancelledTitle(String reason) {
        if (reason == null) {
            return "Order cancelled";
        }
        if (reason.startsWith(OrderLifecycleCommands.MERCHANT_REJECTED)) {
            return "Order rejected by the restaurant";
        }
        return switch (reason) {
            case OrderLifecycleCommands.MERCHANT_TIMEOUT -> "Order cancelled: the restaurant did not respond";
            case OrderLifecycleCommands.PAYMENT_TIMEOUT -> "Order cancelled: payment not received";
            default -> "Order cancelled";
        };
    }


    /**
     * Every cancellation of an order with a captured payment above 0.00 (the customer's, an admin's, the restaurant's
     * reject, the accept timeout) says the payment will be refunded automatically: the cancel opened its refund (ADR
     * 0002 D12). Without one, a reject or an accept timeout points the customer to support (a paid order without a
     * capture: an admin marked it PAID, or it was paid before payments were recorded), and the other cancellations say
     * nothing about a payment. A payment timeout only cancels an unpaid order. A customer's cancel after the rider picked
     * the order up refunded only a share of it (ADR 0002 section 27, follow-up 17), and the note says how much.
     *
     * @param orderNumber the number shown to the customer
     * @param orderId     the order's internal id
     * @param data        the order.cancelled payload: its reason and its total
     */
    private String buildCancelledMessage(String orderNumber, Long orderId, OrderEventPayload data) {
        String reason = data.reason();
        if (reason != null && reason.startsWith(OrderLifecycleCommands.MERCHANT_REJECTED)) {
            String note = reason.substring(OrderLifecycleCommands.MERCHANT_REJECTED.length()).replaceFirst("^:\\s*", "");
            return "The restaurant could not take order #" + orderNumber
                    + (note.isBlank() ? "" : ": " + note)
                    + ". " + paymentNote(orderId);
        }
        if (OrderLifecycleCommands.MERCHANT_TIMEOUT.equals(reason)) {
            return "Order #" + orderNumber + " was cancelled because the restaurant did not accept it in time. "
                    + paymentNote(orderId);
        }
        if (OrderLifecycleCommands.PAYMENT_TIMEOUT.equals(reason)) {
            return "Order #" + orderNumber + " was cancelled because it was not paid in time.";
        }
        if (!refundsAutomatically(orderId)) {
            return "Order #" + orderNumber + " was cancelled.";
        }
        if (OrderLifecycleCommands.CUSTOMER_CANCELLED.equals(reason)
                && orderRepository.findPickedUpAt(orderId).isPresent()) {
            return "Order #" + orderNumber + " was cancelled after the rider picked it up. " + pickedUpRefundNote(data);
        }
        return "Order #" + orderNumber + " was cancelled. " + AUTOMATIC_REFUND_NOTE;
    }


    /**
     * What a customer's cancel after the pickup refunds: the configured share of the total, computed as the after-sales
     * module computes it (half-even to the currency's minor unit), or nothing. A pickup is recorded only before the
     * order is cancelled, so one read now was there when the cancel decided the refund.
     */
    private String pickedUpRefundNote(OrderEventPayload data) {
        if (data.totalPrice() == null) {
            return "Part of your payment will be refunded automatically.";
        }
        Money total = money(data.totalPrice(), data.currencyCode());
        Money share = total.multiply(pickedUpRefundShare);
        return share.isPositive()
                ? share + " of your " + total + " payment will be refunded automatically."
                : "Your payment will not be refunded.";
    }


    /**
     * What happens to the payment of a paid order the restaurant rejected or did not accept in time: refunded
     * automatically, or (without a capture to refund) handled by support.
     */
    private String paymentNote(Long orderId) {
        return refundsAutomatically(orderId) ? AUTOMATIC_REFUND_NOTE : PAYMENT_SUPPORT_NOTE;
    }


    /**
     * True when the order has a captured payment above 0.00, which its cancel refunds automatically (ADR 0002 D12).
     */
    private boolean refundsAutomatically(Long orderId) {
        return orderPaymentRepository.findByOrderId(orderId)
                .map(payment -> payment.captured().isPositive())
                .orElse(false);
    }


    private String buildMessage(OrderEventEnvelope event, String orderNumber, Long orderId) {
        int itemCount = event.data().items() == null ? 0 : event.data().items().size();
        return switch (event.eventType()) {
            case "order.paid" -> "Order #" + orderNumber + " was paid successfully with " + itemCount + " item(s).";
            case "order.created" -> "Order #" + orderNumber + " was placed and sent for processing.";
            case "order.accepted" -> "Order #" + orderNumber + " was accepted by the restaurant.";
            case "order.preparing" -> "Order #" + orderNumber + " is now being prepared.";
            case "order.completed" -> "Order #" + orderNumber + " is completed and ready.";
            case "order.cancelled" -> buildCancelledMessage(orderNumber, orderId, event.data());
            default -> "Order #" + orderNumber + " has a new event: " + event.eventType() + ".";
        };
    }


    /**
     * The notification of an after-sales event, or empty for an event that tells the customer nothing new.
     */
    private Optional<Notice> afterSalesNotice(String eventType, AfterSalesCasePayload data, String orderNumber) {
        String order = "order #" + orderNumber;
        Money requested = money(firstOf(data.requestedAmount()), data.currency());
        return Optional.ofNullable(switch (eventType) {
            case FulfilmentEvents.AFTER_SALES_CASE_OPENED -> new Notice(
                    "Refund requested",
                    "Your refund request of " + requested + " for " + order + " was sent to the restaurant."
                            + (data.deadlineAt() == null ? "" : " If it does not answer by "
                            + deadline(data.deadlineAt()) + ", the request is approved automatically.")
            );
            case FulfilmentEvents.AFTER_SALES_OFFER_MADE -> new Notice(
                    "Partial refund offered",
                    withNote("The restaurant offers " + money(firstOf(data.offeredAmount(), data.requestedAmount()),
                            data.currency()) + " instead of the " + requested + " you requested for " + order,
                            data.merchantNote())
                            + (data.deadlineAt() == null ? "" : " Reply by " + deadline(data.deadlineAt())
                            + ", or the offer is accepted automatically.")
            );
            case FulfilmentEvents.AFTER_SALES_REQUEST_REJECTED -> CUSTOMER_DECLINE_OFFER_ACTION.equals(data.action())
                    ? new Notice(
                            "Offer declined",
                            "You declined the restaurant's offer for " + order + "." + stepIn(data.deadlineAt()))
                    : new Notice(
                            "Refund request declined",
                            withNote("The restaurant declined your refund request for " + order, data.merchantNote())
                                    + stepIn(data.deadlineAt()));
            case FulfilmentEvents.AFTER_SALES_CASE_ESCALATED -> new Notice(
                    "Platform review requested",
                    "The platform will review your refund request of " + requested + " for " + order + "."
                            + (data.deadlineAt() == null ? "" : " If it has not decided by "
                            + deadline(data.deadlineAt()) + ", the request is approved automatically.")
            );
            case FulfilmentEvents.AFTER_SALES_REFUND_APPROVED -> new Notice(
                    "Refund approved",
                    REFUND_APPROVED_LEADS.getOrDefault(data.resolution() == null ? "" : data.resolution(), "")
                            + money(firstOf(data.approvedAmount(), data.requestedAmount()), data.currency())
                            + " for " + order + " will be refunded."
            );
            case FulfilmentEvents.AFTER_SALES_CASE_REFUNDED -> {
                Money refunded = money(
                        firstOf(data.refundedAmount(), data.approvedAmount(), data.requestedAmount()), data.currency());
                // A customer's cancel after the pickup refunds a share of the order (section 27, follow-up 17).
                boolean share = requested.isGreaterThan(refunded);
                yield new Notice(
                        "Refund completed",
                        !ORDER_CANCELLED_KIND.equals(data.kind())
                                ? refunded + " for " + order + " was refunded."
                                : share
                                ? refunded + " of your " + requested + " payment for cancelled " + order + " was refunded."
                                : "Your payment of " + refunded + " for cancelled " + order + " was refunded."
                );
            }
            case FulfilmentEvents.AFTER_SALES_CASE_CLOSED -> new Notice(
                    "Refund request closed",
                    closedMessage(data.closeReason(), order)
            );
            default -> null;
        });
    }


    private static String closedMessage(String closeReason, String order) {
        if (WITHDRAWN.equals(closeReason)) {
            return "You withdrew your refund request for " + order + ".";
        }
        if (CUSTOMER_TIMEOUT.equals(closeReason)) {
            return "Your refund request for " + order
                    + " was closed because the platform was not asked to step in before the deadline.";
        }
        if (ARBITRATION_REJECTED.equals(closeReason)) {
            return "The platform rejected your refund request for " + order + ".";
        }
        return "Your refund request for " + order + " was closed.";
    }


    /**
     * The customer's next step after a rejection, with its deadline.
     */
    private static String stepIn(LocalDateTime deadlineAt) {
        return deadlineAt == null
                ? " You can ask the platform to step in."
                : " You can ask the platform to step in by " + deadline(deadlineAt) + ".";
    }


    /**
     * {@code sentence} followed by the merchant's note, ended as a sentence.
     */
    private static String withNote(String sentence, String note) {
        if (note == null || note.isBlank()) {
            return sentence + ".";
        }
        String trimmed = note.strip();
        char last = trimmed.charAt(trimmed.length() - 1);
        return sentence + ": " + trimmed + (last == '.' || last == '!' || last == '?' ? "" : ".");
    }


    private static String deadline(LocalDateTime deadlineAt) {
        return DEADLINE_FORMAT.format(deadlineAt);
    }


    /**
     * An amount of the event in its currency; an event without one is in USD, like every event before money carried a
     * currency.
     */
    private static Money money(BigDecimal amount, String currency) {
        return Money.of(amount, currency == null ? Money.LEGACY_CURRENCY.getCurrencyCode() : currency);
    }


    private static BigDecimal firstOf(BigDecimal... amounts) {
        for (BigDecimal amount : amounts) {
            if (amount != null) {
                return amount;
            }
        }
        throw new IllegalArgumentException("After-sales event payload is missing its amount");
    }


    /**
     * The title and message of a notification.
     */
    private record Notice(String title, String message) {
    }
}
