package com.laioffer.onlineorder.controller;


import com.laioffer.onlineorder.audit.AuditActorResolver;
import com.laioffer.onlineorder.model.AbandonOutboxEventBody;
import com.laioffer.onlineorder.model.OutboxEventDto;
import com.laioffer.onlineorder.service.OrderEventOutboxService;
import com.laioffer.onlineorder.service.OutboxAdminService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


import java.security.Principal;
import java.util.List;


@RestController
public class OutboxAdminController {

    private final OrderEventOutboxService orderEventOutboxService;
    private final OutboxAdminService outboxAdminService;
    private final AuditActorResolver auditActorResolver;


    public OutboxAdminController(
            OrderEventOutboxService orderEventOutboxService,
            OutboxAdminService outboxAdminService,
            AuditActorResolver auditActorResolver
    ) {
        this.orderEventOutboxService = orderEventOutboxService;
        this.outboxAdminService = outboxAdminService;
        this.auditActorResolver = auditActorResolver;
    }


    @GetMapping("/admin/outbox/events/failed")
    public List<OutboxEventDto> getFailedEvents(@RequestParam(defaultValue = "50") int limit) {
        return orderEventOutboxService.getFailedEvents(limit);
    }


    @PostMapping("/admin/outbox/events/{eventId}/retry")
    public OutboxEventDto retryFailedEvent(Principal principal, @PathVariable Long eventId) {
        return outboxAdminService.retryFailedEvent(eventId, auditActorResolver.resolve(principal));
    }


    /**
     * Gives up on a FAILED event that can never be published, releasing the later events of its aggregate. Last resort
     * after retries: its consumers never receive it (docs/operations.md section 8).
     */
    @PostMapping("/admin/outbox/events/{eventId}/abandon")
    public OutboxEventDto abandonFailedEvent(
            Principal principal,
            @PathVariable Long eventId,
            @Valid @RequestBody AbandonOutboxEventBody body
    ) {
        return outboxAdminService.abandonFailedEvent(eventId, body.reason().trim(), auditActorResolver.resolve(principal));
    }
}
