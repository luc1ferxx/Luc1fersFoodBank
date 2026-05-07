package com.laioffer.onlineorder.controller;


import com.laioffer.onlineorder.model.OutboxEventDto;
import com.laioffer.onlineorder.service.OrderEventOutboxService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


import java.util.List;


@RestController
public class OutboxAdminController {

    private final OrderEventOutboxService orderEventOutboxService;


    public OutboxAdminController(OrderEventOutboxService orderEventOutboxService) {
        this.orderEventOutboxService = orderEventOutboxService;
    }


    @GetMapping("/admin/outbox/events/failed")
    public List<OutboxEventDto> getFailedEvents(@RequestParam(defaultValue = "50") int limit) {
        return orderEventOutboxService.getFailedEvents(limit);
    }


    @PostMapping("/admin/outbox/events/{eventId}/retry")
    public OutboxEventDto retryFailedEvent(@PathVariable Long eventId) {
        return orderEventOutboxService.retryFailedEvent(eventId);
    }
}
