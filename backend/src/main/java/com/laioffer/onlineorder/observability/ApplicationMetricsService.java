package com.laioffer.onlineorder.observability;


import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;


@Service
public class ApplicationMetricsService {

    private final MeterRegistry meterRegistry;


    public ApplicationMetricsService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }


    public void recordCheckout(String status) {
        recordCheckoutSuccess(status);
        meterRegistry.counter("onlineorder.checkout.completed", "status", normalize(status)).increment();
    }


    public void recordCheckoutSuccess(String status) {
        meterRegistry.counter(
                "onlineorder.checkout.requests",
                "result", "success",
                "status", normalize(status)
        ).increment();
    }


    public void recordCheckoutFailure(String reason) {
        meterRegistry.counter(
                "onlineorder.checkout.requests",
                "result", "failure",
                "reason", normalize(reason)
        ).increment();
    }


    public void recordOrderTransition(String fromStatus, String toStatus) {
        meterRegistry.counter(
                "onlineorder.order.transition",
                "from", normalize(fromStatus),
                "to", normalize(toStatus)
        ).increment();
    }


    /**
     * A committed step of an after-sales case: its opening or a transition of the after-sales state machine.
     *
     * @param action     OPEN or ORDER_CANCELLED for an opening, otherwise the after-sales trigger
     * @param fromStatus the status the case left; none for an opening
     * @param toStatus   the status the case entered
     */
    public void recordAfterSalesStep(String action, String fromStatus, String toStatus) {
        meterRegistry.counter(
                "onlineorder.aftersales.steps",
                "action", normalize(action),
                "from", normalize(fromStatus),
                "to", normalize(toStatus)
        ).increment();
    }


    /**
     * An after-sales deadline the poller handled.
     *
     * @param trigger the timeout: MERCHANT_TIMEOUT, OFFER_TIMEOUT, CUSTOMER_TIMEOUT or ARBITRATION_TIMEOUT
     * @param outcome fired (the step committed), skipped (the after-sales machine refused it, so nothing was written) or
     *                failed (it rolled back and the deadline backs off)
     */
    public void recordAfterSalesDeadline(String trigger, String outcome) {
        meterRegistry.counter(
                "onlineorder.aftersales.deadlines",
                "trigger", normalize(trigger),
                "outcome", normalize(outcome)
        ).increment();
    }


    /**
     * A refund the refund executor handled, counted once its transaction committed.
     *
     * @param outcome succeeded (the gateway refunded it and its case is REFUNDED), retried (the gateway call failed and
     *                the refund waits for its backoff), failed (given up: a permanent failure, the attempts used up or a
     *                refund that no longer matches its case or payment), superseded (another executor reclaimed it
     *                after the lease expired, so this result was discarded), unrecorded (the result could not be
     *                recorded; the refund is reclaimed once its lease expires) or skipped (claimed but not due after
     *                all)
     */
    public void recordAfterSalesRefund(String outcome) {
        meterRegistry.counter(
                "onlineorder.aftersales.refunds",
                "outcome", normalize(outcome)
        ).increment();
    }


    /**
     * @param decision accepted, rejected or timeout (the merchant did not answer within app.fulfilment.accept-timeout)
     */
    public void recordMerchantDecision(String decision) {
        meterRegistry.counter(
                "onlineorder.fulfilment.merchant.decisions",
                "decision", normalize(decision)
        ).increment();
    }


    /**
     * A paid order reached its restaurant's merchant inbox.
     *
     * @param staffing staffed (the restaurant had an active merchant account, so the order gets an accept deadline) or
     *                 unstaffed (it has none: the order is never auto-cancelled and waits for a merchant or an admin)
     */
    public void recordMerchantOrderReceived(String staffing) {
        meterRegistry.counter(
                "onlineorder.fulfilment.merchant.orders_received",
                "staffing", normalize(staffing)
        ).increment();
    }


    /**
     * @param deadlineType PAYMENT or ACCEPT
     * @param result       cancelled (the order was still waiting) or skipped (it had moved on)
     */
    public void recordFulfilmentTimeout(String deadlineType, String result) {
        meterRegistry.counter(
                "onlineorder.fulfilment.timeouts",
                "type", normalize(deadlineType),
                "result", normalize(result)
        ).increment();
    }


    /**
     * @param result assigned; duplicate (the order already had a rider); cancelled (order.cancelled was handled first,
     *               so no rider was assigned); or no_rider_attempt (an attempt found no active rider and failed for a
     *               retry: counted once per listener attempt and DLT replay, not once per order)
     */
    public void recordDispatchAssignment(String result) {
        meterRegistry.counter(
                "onlineorder.fulfilment.dispatch.assignments",
                "result", normalize(result)
        ).increment();
    }


    public void recordOutboxPublishResult(String topic, boolean success) {
        meterRegistry.counter(
                "onlineorder.outbox.publish",
                "topic", normalize(topic),
                "result", success ? "success" : "failure"
        ).increment();
    }


    /**
     * An event that broke its published contract (README "Event contracts").
     *
     * @param side      producer (an event the application tried to write: a bug, its transaction was rolled back) or
     *                  consumer (a record read from Kafka: dead-lettered without retries)
     * @param topic     the contract topic (order-events, delivery-events, merchant-events, after-sales-events), or
     *                  unknown
     * @param eventType a published event type, or unknown
     */
    public void recordEventSchemaViolation(String side, String topic, String eventType) {
        meterRegistry.counter(
                "onlineorder.events.schema_violations",
                "side", normalize(side),
                "topic", normalize(topic),
                "event_type", normalize(eventType)
        ).increment();
    }


    /**
     * A consumer skipped an event whose sequence was not above the last one it applied for the event's aggregate (a
     * late or replayed event that later events already superseded).
     */
    public void recordStaleEventSkipped(String consumer, String eventType) {
        meterRegistry.counter(
                "onlineorder.events.stale_skipped",
                "consumer", normalize(consumer),
                "event_type", normalize(eventType)
        ).increment();
    }


    public void recordDeadLetterStored(String sourceTopic) {
        meterRegistry.counter(
                "onlineorder.dead_letter.stored",
                "source_topic", normalize(sourceTopic)
        ).increment();
    }


    public void recordDeadLetterReplay(boolean success) {
        meterRegistry.counter(
                "onlineorder.dead_letter.replay",
                "result", success ? "success" : "failure"
        ).increment();
    }


    public void recordRateLimitRejection(String route) {
        meterRegistry.counter(
                "onlineorder.security.rate_limit.rejected",
                "route", normalize(route)
        ).increment();
    }


    public void recordRateLimitStoreFailure(String route) {
        meterRegistry.counter(
                "onlineorder.security.rate_limit.store_failure",
                "route", normalize(route)
        ).increment();
    }


    public void recordCacheError(String cacheName, String operation) {
        meterRegistry.counter(
                "onlineorder.cache.errors",
                "cache", normalize(cacheName),
                "operation", normalize(operation)
        ).increment();
    }


    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.trim().toLowerCase().replace(' ', '_');
    }
}
