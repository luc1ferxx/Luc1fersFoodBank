package com.laioffer.onlineorder.service;


import com.laioffer.onlineorder.messaging.OrderEventEnvelope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


@Service
public class OrderEventHandlerService {

    static final String ORDER_NOTIFICATION_CONSUMER = "order-notification-consumer";

    private final OrderNotificationService orderNotificationService;
    private final ProcessedEventService processedEventService;


    public OrderEventHandlerService(
            OrderNotificationService orderNotificationService,
            ProcessedEventService processedEventService
    ) {
        this.orderNotificationService = orderNotificationService;
        this.processedEventService = processedEventService;
    }


    @Transactional
    public void handle(OrderEventEnvelope event) {
        if (!processedEventService.markIfUnseen(ORDER_NOTIFICATION_CONSUMER, event)) {
            return;
        }
        orderNotificationService.recordOrderEvent(event);
    }
}
