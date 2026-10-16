package com.laioffer.onlineorder.service;


import com.laioffer.onlineorder.entity.OrderNotificationEntity;
import com.laioffer.onlineorder.fulfilment.order.OrderLifecycleCommands;
import com.laioffer.onlineorder.messaging.DeliveryEventEnvelope;
import com.laioffer.onlineorder.messaging.DeliveryEventPayload;
import com.laioffer.onlineorder.messaging.OrderEventEnvelope;
import com.laioffer.onlineorder.messaging.OrderEventPayload;
import com.laioffer.onlineorder.model.OrderNotificationDto;
import com.laioffer.onlineorder.repository.OrderNotificationRepository;
import org.springframework.stereotype.Service;


import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;


@Service
public class OrderNotificationService {

    private static final String PAYMENT_SUPPORT_NOTE = "Please contact support about your payment.";

    private final OrderNotificationRepository orderNotificationRepository;


    public OrderNotificationService(OrderNotificationRepository orderNotificationRepository) {
        this.orderNotificationRepository = orderNotificationRepository;
    }


    public void recordOrderEvent(OrderEventEnvelope event) {
        validate(event);
        OrderEventPayload data = event.data();
        orderNotificationRepository.insertIfAbsent(
                event.aggregateId(),
                data.customerId(),
                event.eventType(),
                buildTitle(event),
                buildMessage(event),
                LocalDateTime.now()
        );
    }


    /**
     * Tells the customer about their delivery: rider assigned, picked up, delivered. A cancelled delivery gets no
     * notification of its own; the order's cancellation notification covers it. One notification per order and
     * event type (uq_notification_order_event), so a redelivered event adds nothing.
     */
    public void recordDeliveryEvent(DeliveryEventEnvelope event) {
        if (event == null || event.data() == null || event.data().orderId() == null || event.data().customerId() == null) {
            throw new IllegalArgumentException("Delivery event payload is missing required identifiers");
        }
        DeliveryEventPayload data = event.data();
        String riderName = data.riderName() == null || data.riderName().isBlank() ? "Your rider" : data.riderName();
        String title;
        String message;
        switch (event.eventType() == null ? "" : event.eventType()) {
            case "delivery.assigned" -> {
                title = "Rider assigned";
                message = riderName + " will deliver order #" + data.orderId() + ".";
            }
            case "delivery.picked_up" -> {
                title = "Order on the way";
                message = riderName + " picked up order #" + data.orderId() + " and is on the way.";
            }
            case "delivery.delivered" -> {
                title = "Order delivered";
                message = "Order #" + data.orderId() + " was delivered. Enjoy your meal!";
            }
            default -> {
                return;
            }
        }
        orderNotificationRepository.insertIfAbsent(
                data.orderId(),
                data.customerId(),
                event.eventType(),
                title,
                message,
                LocalDateTime.now()
        );
    }


    public List<OrderNotificationDto> getNotificationsByCustomerId(Long customerId) {
        return getNotificationsByCustomerId(customerId, 50);
    }


    public List<OrderNotificationDto> getNotificationsByCustomerId(Long customerId, int limit) {
        List<OrderNotificationDto> results = new ArrayList<>();
        for (OrderNotificationEntity entity : orderNotificationRepository.findPageByCustomerIdOrderByCreatedAtDesc(customerId, normalizeLimit(limit))) {
            results.add(new OrderNotificationDto(entity));
        }
        return results;
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
     * Refunds are not automated yet (README "Order fulfilment", follow-ups), so a cancelled paid order points the
     * customer to support instead of promising a refund.
     */
    private String buildCancelledMessage(Long orderId, String reason) {
        if (reason != null && reason.startsWith(OrderLifecycleCommands.MERCHANT_REJECTED)) {
            String note = reason.substring(OrderLifecycleCommands.MERCHANT_REJECTED.length()).replaceFirst("^:\\s*", "");
            return "The restaurant could not take order #" + orderId
                    + (note.isBlank() ? "" : ": " + note)
                    + ". " + PAYMENT_SUPPORT_NOTE;
        }
        if (OrderLifecycleCommands.MERCHANT_TIMEOUT.equals(reason)) {
            return "Order #" + orderId + " was cancelled because the restaurant did not accept it in time. "
                    + PAYMENT_SUPPORT_NOTE;
        }
        if (OrderLifecycleCommands.PAYMENT_TIMEOUT.equals(reason)) {
            return "Order #" + orderId + " was cancelled because it was not paid in time.";
        }
        return "Order #" + orderId + " was cancelled.";
    }


    private String buildMessage(OrderEventEnvelope event) {
        int itemCount = event.data().items() == null ? 0 : event.data().items().size();
        return switch (event.eventType()) {
            case "order.paid" -> "Order #" + event.aggregateId() + " was paid successfully with " + itemCount + " item(s).";
            case "order.created" -> "Order #" + event.aggregateId() + " was placed and sent for processing.";
            case "order.accepted" -> "Order #" + event.aggregateId() + " was accepted by the restaurant.";
            case "order.preparing" -> "Order #" + event.aggregateId() + " is now being prepared.";
            case "order.completed" -> "Order #" + event.aggregateId() + " is completed and ready.";
            case "order.cancelled" -> buildCancelledMessage(event.aggregateId(), event.data().reason());
            default -> "Order #" + event.aggregateId() + " has a new event: " + event.eventType() + ".";
        };
    }
}
