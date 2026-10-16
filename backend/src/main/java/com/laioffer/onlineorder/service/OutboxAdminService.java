package com.laioffer.onlineorder.service;


import com.laioffer.onlineorder.audit.AuditActions;
import com.laioffer.onlineorder.audit.AuditActor;
import com.laioffer.onlineorder.audit.AuditLogService;
import com.laioffer.onlineorder.entity.OutboxEventEntity;
import com.laioffer.onlineorder.model.OutboxEventDto;
import com.laioffer.onlineorder.repository.OutboxEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


import java.util.LinkedHashMap;
import java.util.Map;


/**
 * The admin outbox retry (POST /admin/outbox/events/{eventId}/retry) with its audit log entry, in one transaction.
 */
@Service
public class OutboxAdminService {

    private final OrderEventOutboxService orderEventOutboxService;
    private final OutboxEventRepository outboxEventRepository;
    private final AuditLogService auditLogService;


    public OutboxAdminService(
            OrderEventOutboxService orderEventOutboxService,
            OutboxEventRepository outboxEventRepository,
            AuditLogService auditLogService
    ) {
        this.orderEventOutboxService = orderEventOutboxService;
        this.outboxEventRepository = outboxEventRepository;
        this.auditLogService = auditLogService;
    }


    /**
     * Resets a FAILED event to PENDING (see {@link OrderEventOutboxService#retryFailedEvent}) and records who did it,
     * with the attempts and last error the reset clears. A refused retry (404, not FAILED) records nothing.
     */
    @Transactional
    public OutboxEventDto retryFailedEvent(Long eventId, AuditActor actor) {
        // Locked first so the values recorded are the ones the reset below overwrites.
        OutboxEventEntity failed = outboxEventRepository.lockById(eventId);
        OutboxEventDto retried = orderEventOutboxService.retryFailedEvent(eventId);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("event_id", retried.eventId());
        details.put("event_type", retried.eventType());
        details.put("aggregate_type", retried.aggregateType());
        details.put("aggregate_id", retried.aggregateId());
        if (failed != null) {
            details.put("previous_attempts", failed.attempts());
            details.put("previous_last_error", failed.lastError());
        }
        auditLogService.record(actor, AuditActions.OUTBOX_EVENT_RETRY, AuditActions.TARGET_OUTBOX_EVENT, eventId, details);
        return retried;
    }
}
