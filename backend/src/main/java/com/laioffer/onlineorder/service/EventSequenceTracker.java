package com.laioffer.onlineorder.service;


import com.laioffer.onlineorder.messaging.EventEnvelope;
import com.laioffer.onlineorder.observability.ApplicationMetricsService;
import com.laioffer.onlineorder.repository.ConsumerAggregateSequenceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;


import java.time.LocalDateTime;


/**
 * Stale-event detection for consumers that keep per-aggregate state (docs/fulfilment.md "Out-of-order events"). The
 * outbox numbers each aggregate's events (the envelope's {@code sequence}) and publishes them in that order, but a dead
 * letter replayed later still reaches a consumer after events that superseded it. Such a consumer calls
 * {@link #advance} in its transaction, after its processed_events mark and only for the events it applies:
 * <ul>
 *     <li>true: the event is newer than every event of the aggregate this consumer applied, and is now the last one;</li>
 *     <li>false: the consumer already applied this or a later event of the aggregate. The event is stale; the consumer
 *     applies nothing (or only facts no later event carries) and the skip is counted in
 *     {@code onlineorder.events.stale_skipped}.</li>
 * </ul>
 * An event without a sequence (written before V9) is always applied, as before, and leaves the position unchanged. A
 * consumer must only call this for event types it applies: an ignored event that moved the position would make a
 * replayed earlier event it does apply look stale.
 */
@Service
public class EventSequenceTracker {

    private static final Logger LOGGER = LoggerFactory.getLogger(EventSequenceTracker.class);

    private final ConsumerAggregateSequenceRepository repository;
    private final ApplicationMetricsService metricsService;


    public EventSequenceTracker(
            ConsumerAggregateSequenceRepository repository,
            ApplicationMetricsService metricsService
    ) {
        this.repository = repository;
        this.metricsService = metricsService;
    }


    /**
     * @return true to apply the event, false when it is stale for {@code consumerName}
     */
    public boolean advance(String consumerName, EventEnvelope event) {
        Long sequence = event.sequence();
        if (sequence == null || event.aggregateType() == null || event.aggregateId() == null) {
            return true;
        }

        String aggregateType = event.aggregateType();
        Long aggregateId = event.aggregateId();
        LocalDateTime now = LocalDateTime.now();
        if (repository.advanceIfNewer(consumerName, aggregateType, aggregateId, sequence, now) == 1
                || repository.insertIfAbsent(consumerName, aggregateType, aggregateId, sequence, now) == 1
                // A concurrent transaction inserted the row between the two statements above.
                || repository.advanceIfNewer(consumerName, aggregateType, aggregateId, sequence, now) == 1) {
            return true;
        }

        metricsService.recordStaleEventSkipped(consumerName, event.eventType());
        LOGGER.info(
                "Skipping stale event {} ({} #{} of {} {}) in {}: a later event of the aggregate was applied already "
                        + "(last applied #{})",
                event.eventId(),
                event.eventType(),
                sequence,
                aggregateType,
                aggregateId,
                consumerName,
                repository.findLastSequence(consumerName, aggregateType, aggregateId)
        );
        return false;
    }
}
