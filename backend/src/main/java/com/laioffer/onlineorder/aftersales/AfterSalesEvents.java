package com.laioffer.onlineorder.aftersales;


import com.laioffer.onlineorder.messaging.AfterSalesCaseLinePayload;
import com.laioffer.onlineorder.messaging.AfterSalesCasePayload;
import com.laioffer.onlineorder.messaging.AfterSalesEventEnvelope;


import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;


/**
 * How the after-sales module writes its events (ADR 0002 section 10): the contract version and aggregate, the data of
 * a case after a step, and the envelope. The single writer stores them with
 * {@code OrderEventOutboxService.enqueueEvent(app.after-sales.topic, String.valueOf(orderId), envelope)} in the
 * transaction of the step, which checks them against {@code event-schemas/after-sales-events/<event_type>/v1.json}.
 */
public final class AfterSalesEvents {

    /**
     * The schema version of the after-sales events written here.
     */
    public static final int AFTER_SALES_EVENT_VERSION = 1;

    /**
     * The aggregate of every after-sales event: the case (aggregate_id = the case id). The record key is the order id,
     * so a case's events share a partition with its order's.
     */
    public static final String AGGREGATE_TYPE = "AFTER_SALES_CASE";


    private AfterSalesEvents() {
    }


    /**
     * The data of an event: {@code current} as saved by the step, with its lines.
     *
     * @param orderNo   the order's public number; null for an order without one
     * @param action    OPEN or ORDER_CANCELLED for the opening, otherwise the {@link AfterSalesTrigger} name
     * @param actorType who took the step
     * @param actorId   the customers.id of the customer, merchant or admin; null for SYSTEM
     */
    public static AfterSalesCasePayload payload(
            AfterSalesCaseEntity current,
            List<AfterSalesCaseLineEntity> lines,
            Long orderNo,
            String action,
            AfterSalesActor actorType,
            Long actorId
    ) {
        return new AfterSalesCasePayload(
                current.id(),
                current.orderId(),
                orderNo,
                current.customerId(),
                current.restaurantId(),
                current.kind(),
                current.attemptNo(),
                current.status(),
                Objects.requireNonNull(action, "action"),
                actorType.name(),
                actorId,
                current.version(),
                current.reasonCode(),
                current.description(),
                current.requestedAmount(),
                current.offeredAmount(),
                current.approvedAmount(),
                current.refundedAmount(),
                current.currency(),
                current.merchantDecision(),
                current.merchantNote(),
                current.resolution(),
                current.closeReason(),
                current.deadlineAt(),
                lines.stream().map(AfterSalesEvents::line).toList(),
                current.createdAt()
        );
    }


    /**
     * The envelope of an event, without a sequence (the outbox assigns it). The correlation id is the request's
     * idempotency key, or the event id when the step had none (a merchant command, the poller), as for order events.
     *
     * @param eventType      {@code AFTER_SALES_CASE_OPENED} for the opening, otherwise
     *                       {@link AfterSalesStatus#eventType()} of the status the step moved to
     * @param occurredAt     the time of the step
     * @param idempotencyKey the Idempotency-Key of the request that took the step, if any
     */
    public static AfterSalesEventEnvelope envelope(
            String eventType,
            AfterSalesCasePayload data,
            LocalDateTime occurredAt,
            String idempotencyKey
    ) {
        String eventId = UUID.randomUUID().toString();
        String key = idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey.trim();
        return new AfterSalesEventEnvelope(
                eventId,
                AFTER_SALES_EVENT_VERSION,
                eventType,
                AGGREGATE_TYPE,
                data.caseId(),
                occurredAt,
                key == null ? eventId : key,
                key,
                data
        );
    }


    private static AfterSalesCaseLinePayload line(AfterSalesCaseLineEntity line) {
        return new AfterSalesCaseLinePayload(
                line.orderItemId(),
                line.menuItemName(),
                line.quantity(),
                line.unitPrice(),
                line.amount(),
                line.currency()
        );
    }
}
