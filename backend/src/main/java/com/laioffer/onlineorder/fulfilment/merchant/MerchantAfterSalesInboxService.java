package com.laioffer.onlineorder.fulfilment.merchant;


import com.laioffer.onlineorder.fulfilment.FulfilmentEvents;
import com.laioffer.onlineorder.messaging.AfterSalesCasePayload;
import com.laioffer.onlineorder.messaging.AfterSalesEventEnvelope;
import com.laioffer.onlineorder.service.EventSequenceTracker;
import com.laioffer.onlineorder.service.ProcessedEventService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


import java.time.LocalDateTime;
import java.util.Set;


/**
 * Builds the merchant's after-sales inbox from after-sales events (merchant after-sales consumer group, ADR 0002
 * section 10.3). Every event carries the whole case after its step, so each one upserts the case's row
 * ({@link MerchantAfterSalesProjection}): the first event the group sees adds the row, a later version moves it
 * forward, an older one never moves it back.
 *
 * <p>Only customers' requests reach a merchant: the events of the automatic refund of a cancelled order
 * (kind ORDER_CANCELLED) are dropped before anything is written, without a processed_events mark. Every other event is
 * applied once per consumer (processed_events, in the same transaction as the row) and in the order of its case's
 * events: one older (by sequence) than the last event of the case this consumer applied, such as a replayed dead
 * letter, is stale and skipped, and the version condition of the upsert keeps the row from going back even for an
 * event without a sequence.
 */
@Service
public class MerchantAfterSalesInboxService {

    static final String MERCHANT_AFTER_SALES_CONSUMER = "merchant-aftersales-consumer";

    static final String CUSTOMER_REQUEST = "CUSTOMER_REQUEST";

    private static final Set<String> HANDLED_EVENT_TYPES = Set.of(
            FulfilmentEvents.AFTER_SALES_CASE_OPENED,
            FulfilmentEvents.AFTER_SALES_OFFER_MADE,
            FulfilmentEvents.AFTER_SALES_REQUEST_REJECTED,
            FulfilmentEvents.AFTER_SALES_CASE_ESCALATED,
            FulfilmentEvents.AFTER_SALES_REFUND_APPROVED,
            FulfilmentEvents.AFTER_SALES_CASE_REFUNDED,
            FulfilmentEvents.AFTER_SALES_CASE_CLOSED
    );

    private final MerchantAfterSalesProjection projection;
    private final ProcessedEventService processedEventService;
    private final EventSequenceTracker sequenceTracker;


    public MerchantAfterSalesInboxService(
            MerchantAfterSalesProjection projection,
            ProcessedEventService processedEventService,
            EventSequenceTracker sequenceTracker
    ) {
        this.projection = projection;
        this.processedEventService = processedEventService;
        this.sequenceTracker = sequenceTracker;
    }


    @Transactional
    public void handle(AfterSalesEventEnvelope event) {
        if (event == null || !HANDLED_EVENT_TYPES.contains(event.eventType())) {
            return;
        }
        AfterSalesCasePayload data = event.data();
        if (data == null || !CUSTOMER_REQUEST.equals(data.kind()) || data.restaurantId() == null) {
            return;
        }
        if (!processedEventService.markIfUnseen(
                MERCHANT_AFTER_SALES_CONSUMER,
                event.eventId(),
                event.eventId(),
                event.eventType(),
                event.aggregateType(),
                event.aggregateId()
        )) {
            return;
        }
        if (!sequenceTracker.advance(MERCHANT_AFTER_SALES_CONSUMER, event)) {
            // A later event of the case was applied: it carried the whole case, so this one adds nothing.
            return;
        }
        projection.apply(data, LocalDateTime.now());
    }
}
