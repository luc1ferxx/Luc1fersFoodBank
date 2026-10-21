package com.laioffer.onlineorder.aftersales;


import com.laioffer.onlineorder.entity.OutboxEventEntity;
import com.laioffer.onlineorder.fulfilment.FulfilmentEvents;
import com.laioffer.onlineorder.messaging.AfterSalesCaseLinePayload;
import com.laioffer.onlineorder.messaging.AfterSalesCasePayload;
import com.laioffer.onlineorder.messaging.AfterSalesEventEnvelope;
import com.laioffer.onlineorder.messaging.contract.EventContracts;
import com.laioffer.onlineorder.messaging.contract.EventSchemaViolationException;
import com.laioffer.onlineorder.messaging.contract.EventTestSupport;
import com.laioffer.onlineorder.messaging.contract.EventTopic;
import com.laioffer.onlineorder.repository.OutboxEventRepository;
import com.laioffer.onlineorder.service.OrderEventOutboxService;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import tools.jackson.databind.JsonNode;


import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;


/**
 * The after-sales events of ADR 0002 section 10: one type per status a case enters, the data of a case after each step
 * of the machine and of both openings, and that every such event passes its published contract when the outbox stores
 * it on the after-sales topic and reads back unchanged.
 */
class AfterSalesEventsTests {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 21, 12, 0, 30, 123_456_000);

    private static final Long ORDER_NO = 7_254_189_912_938_504_192L;

    private static final Long MERCHANT_ID = 21L;

    private static final Long ADMIN_ID = 1L;

    private static final Long CUSTOMER_ID = 3L;


    @Test
    void eventType_shouldBeOneTypePerStatusWithCaseOpenedForPendingMerchant() {
        Map<AfterSalesStatus, String> expected = new EnumMap<>(AfterSalesStatus.class);
        expected.put(AfterSalesStatus.PENDING_MERCHANT, "aftersales.case_opened");
        expected.put(AfterSalesStatus.COUNTER_OFFERED, "aftersales.offer_made");
        expected.put(AfterSalesStatus.REJECTED, "aftersales.request_rejected");
        expected.put(AfterSalesStatus.IN_ARBITRATION, "aftersales.case_escalated");
        expected.put(AfterSalesStatus.REFUNDING, "aftersales.refund_approved");
        expected.put(AfterSalesStatus.REFUNDED, "aftersales.case_refunded");
        expected.put(AfterSalesStatus.CLOSED, "aftersales.case_closed");

        for (AfterSalesStatus status : AfterSalesStatus.values()) {
            Assertions.assertEquals(expected.get(status), status.eventType(), status.name());
        }
        Assertions.assertEquals(
                Set.of(
                        FulfilmentEvents.AFTER_SALES_CASE_OPENED,
                        FulfilmentEvents.AFTER_SALES_OFFER_MADE,
                        FulfilmentEvents.AFTER_SALES_REQUEST_REJECTED,
                        FulfilmentEvents.AFTER_SALES_CASE_ESCALATED,
                        FulfilmentEvents.AFTER_SALES_REFUND_APPROVED,
                        FulfilmentEvents.AFTER_SALES_CASE_REFUNDED,
                        FulfilmentEvents.AFTER_SALES_CASE_CLOSED
                ),
                Set.copyOf(expected.values())
        );
    }


    @Test
    void payload_shouldCopyTheCaseItsLinesAndTheStep() {
        AfterSalesCaseEntity offered = new CaseBuilder()
                .status(AfterSalesStatus.COUNTER_OFFERED)
                .version(1L)
                .merchant("OFFERED", "We can refund the fries.")
                .offered("5.50")
                .deadline(NOW.plusHours(48))
                .build();

        AfterSalesCasePayload payload = AfterSalesEvents.payload(
                offered, CaseBuilder.LINES, ORDER_NO, "MERCHANT_OFFER", AfterSalesActor.MERCHANT, MERCHANT_ID);

        Assertions.assertEquals(
                new AfterSalesCasePayload(
                        501L, 1001L, ORDER_NO, CUSTOMER_ID, 1L, "CUSTOMER_REQUEST", 1, "COUNTER_OFFERED",
                        "MERCHANT_OFFER", "MERCHANT", MERCHANT_ID, 1L, "MISSING_ITEM", "The fries were missing.",
                        new BigDecimal("16.09"), new BigDecimal("5.50"), null, null, "USD", "OFFERED",
                        "We can refund the fries.", null, null, NOW.plusHours(48),
                        List.of(
                                new AfterSalesCaseLinePayload(
                                        41L, "Whopper Meal", 1, new BigDecimal("10.59"), new BigDecimal("10.59"),
                                        "USD"),
                                new AfterSalesCaseLinePayload(
                                        42L, "Large Fries", 2, new BigDecimal("2.75"), new BigDecimal("5.50"), "USD")
                        ),
                        NOW.minusHours(2)
                ),
                payload
        );
        NullPointerException noAction = Assertions.assertThrows(NullPointerException.class, () -> AfterSalesEvents
                .payload(offered, CaseBuilder.LINES, ORDER_NO, null, AfterSalesActor.MERCHANT, MERCHANT_ID));
        Assertions.assertEquals("action", noAction.getMessage());
    }


    @Test
    void envelope_shouldCorrelateWithTheIdempotencyKeyOrElseTheEventId() {
        AfterSalesCasePayload data = openedRequest();

        AfterSalesEventEnvelope keyed = AfterSalesEvents.envelope(
                FulfilmentEvents.AFTER_SALES_CASE_OPENED, data, NOW, "  open-7f3c ");
        AfterSalesEventEnvelope withoutKey = AfterSalesEvents.envelope(
                FulfilmentEvents.AFTER_SALES_CASE_OPENED, data, NOW, null);
        AfterSalesEventEnvelope blankKey = AfterSalesEvents.envelope(
                FulfilmentEvents.AFTER_SALES_CASE_OPENED, data, NOW, " ");

        Assertions.assertEquals(1, keyed.eventVersion());
        Assertions.assertEquals(AfterSalesEvents.AFTER_SALES_EVENT_VERSION, keyed.eventVersion());
        Assertions.assertEquals("AFTER_SALES_CASE", keyed.aggregateType());
        Assertions.assertEquals(501L, keyed.aggregateId());
        Assertions.assertEquals(NOW, keyed.occurredAt());
        Assertions.assertNull(keyed.sequence());
        Assertions.assertSame(data, keyed.data());
        Assertions.assertEquals("open-7f3c", keyed.correlationId());
        Assertions.assertEquals("open-7f3c", keyed.idempotencyKey());
        Assertions.assertEquals(keyed.eventId(), UUID.fromString(keyed.eventId()).toString());
        for (AfterSalesEventEnvelope unkeyed : List.of(withoutKey, blankKey)) {
            Assertions.assertEquals(unkeyed.eventId(), unkeyed.correlationId());
            Assertions.assertNull(unkeyed.idempotencyKey());
        }
        Assertions.assertNotEquals(withoutKey.eventId(), blankKey.eventId());
        Assertions.assertEquals(7L, keyed.withSequence(7L).sequence());
    }


    @Test
    void bothOpenings_shouldBeStoredAsCaseOpenedOnTheAfterSalesTopic() throws Exception {
        AfterSalesCaseEntity request = new CaseBuilder().build();
        AfterSalesCaseEntity cancellation = new CaseBuilder()
                .kind("ORDER_CANCELLED")
                .request(null, null)
                .status(AfterSalesStatus.REFUNDING)
                .deadline(null)
                .approved("16.09")
                .resolution("ORDER_CANCELLED")
                .build();

        assertStoredAndReadBack(AfterSalesEvents.envelope(
                FulfilmentEvents.AFTER_SALES_CASE_OPENED,
                AfterSalesEvents.payload(request, CaseBuilder.LINES, ORDER_NO, "OPEN", AfterSalesActor.CUSTOMER,
                        CUSTOMER_ID),
                NOW,
                "after-sales-open-7f3c"
        ));
        assertStoredAndReadBack(AfterSalesEvents.envelope(
                FulfilmentEvents.AFTER_SALES_CASE_OPENED,
                AfterSalesEvents.payload(cancellation, CaseBuilder.LINES, ORDER_NO, "ORDER_CANCELLED",
                        AfterSalesActor.SYSTEM, null),
                NOW,
                null
        ));
    }


    @ParameterizedTest
    @EnumSource(AfterSalesTrigger.class)
    void theEventOfEveryStep_shouldPassItsContractWhenStoredAndReadBack(AfterSalesTrigger trigger) throws Exception {
        AfterSalesCaseEntity after = caseAfter(trigger);
        AfterSalesStatus to = AfterSalesStateMachine.DEFINITION.transition(trigger).to();
        AfterSalesActor actor = AfterSalesStateMachine.DEFINITION.transition(trigger).actors().iterator().next();
        Long actorId = switch (actor) {
            case CUSTOMER -> CUSTOMER_ID;
            case MERCHANT -> MERCHANT_ID;
            case ADMIN -> ADMIN_ID;
            case SYSTEM -> null;
        };

        Assertions.assertEquals(to.name(), after.status(), trigger.name());
        JsonNode stored = assertStoredAndReadBack(AfterSalesEvents.envelope(
                to.eventType(),
                AfterSalesEvents.payload(after, CaseBuilder.LINES, ORDER_NO, trigger.name(), actor, actorId),
                NOW,
                actor == AfterSalesActor.CUSTOMER || actor == AfterSalesActor.ADMIN ? "key-" + trigger : null
        ));

        JsonNode data = stored.get("data");
        Assertions.assertEquals(trigger.name(), data.get("action").asString());
        Assertions.assertEquals(actor.name(), data.get("actor_type").asString());
        Assertions.assertEquals(actorId == null, data.get("actor_id") == null, trigger.name());
        Assertions.assertEquals(to.isOpen(), data.get("deadline_at") != null, trigger.name());
    }


    @Test
    void anEventThatBreaksItsContract_shouldNotBeStored() {
        AfterSalesCaseEntity approvedWithoutAmount = new CaseBuilder()
                .status(AfterSalesStatus.REFUNDING)
                .deadline(null)
                .resolution("MERCHANT_TIMEOUT")
                .build();
        AfterSalesCaseEntity rejectedWithoutReason = new CaseBuilder()
                .status(AfterSalesStatus.REJECTED)
                .merchant("REJECTED", null)
                .build();
        AfterSalesCaseEntity requestWithoutDeadline = new CaseBuilder().deadline(null).build();
        AfterSalesCaseEntity lowerCaseCurrency = new CaseBuilder().currency("usd").build();

        assertViolation("approved_amount", FulfilmentEvents.AFTER_SALES_REFUND_APPROVED,
                AfterSalesEvents.payload(approvedWithoutAmount, CaseBuilder.LINES, ORDER_NO, "MERCHANT_TIMEOUT",
                        AfterSalesActor.SYSTEM, null));
        assertViolation("merchant_note", FulfilmentEvents.AFTER_SALES_REQUEST_REJECTED,
                AfterSalesEvents.payload(rejectedWithoutReason, CaseBuilder.LINES, ORDER_NO, "MERCHANT_REJECT",
                        AfterSalesActor.MERCHANT, MERCHANT_ID));
        assertViolation("deadline_at", FulfilmentEvents.AFTER_SALES_CASE_OPENED,
                AfterSalesEvents.payload(requestWithoutDeadline, CaseBuilder.LINES, ORDER_NO, "OPEN",
                        AfterSalesActor.CUSTOMER, CUSTOMER_ID));
        assertViolation("/data/currency", FulfilmentEvents.AFTER_SALES_CASE_OPENED,
                AfterSalesEvents.payload(lowerCaseCurrency, CaseBuilder.LINES, ORDER_NO, "OPEN",
                        AfterSalesActor.CUSTOMER, CUSTOMER_ID));
        assertViolation("/data/lines", FulfilmentEvents.AFTER_SALES_CASE_OPENED,
                AfterSalesEvents.payload(new CaseBuilder().build(), List.of(), ORDER_NO, "OPEN",
                        AfterSalesActor.CUSTOMER, CUSTOMER_ID));
        // An offer recorded as the opening's type: its status is not one a case opens in.
        assertViolation("/data/status", FulfilmentEvents.AFTER_SALES_CASE_OPENED,
                AfterSalesEvents.payload(caseAfter(AfterSalesTrigger.MERCHANT_OFFER), CaseBuilder.LINES, ORDER_NO,
                        "MERCHANT_OFFER", AfterSalesActor.MERCHANT, MERCHANT_ID));
    }


    @Test
    void anAfterSalesEvent_onAnotherTopic_shouldNotBeStored() {
        OutboxEventRepository repository = Mockito.mock(OutboxEventRepository.class);
        AfterSalesEventEnvelope opened = AfterSalesEvents.envelope(
                FulfilmentEvents.AFTER_SALES_CASE_OPENED, openedRequest(), NOW, null);

        EventSchemaViolationException violation = Assertions.assertThrows(
                EventSchemaViolationException.class,
                () -> outbox(repository).enqueueEvent("order-events", "1001", opened)
        );

        Assertions.assertTrue(
                violation.getMessage().contains("aftersales.case_opened is published on after-sales-events"),
                violation.getMessage()
        );
        Mockito.verify(repository, Mockito.never()).save(Mockito.any());
    }


    @Test
    void afterSalesEventsTopic_shouldBeCreatedWithTheOrderTopicsPartitions() {
        NewTopic topic = new AfterSalesKafkaConfig().afterSalesEventsTopic("after-sales-events", 3, (short) 1);

        Assertions.assertEquals("after-sales-events", topic.name());
        Assertions.assertEquals(3, topic.numPartitions());
        Assertions.assertEquals((short) 1, topic.replicationFactor());
    }


    /**
     * Stores {@code envelope} through the outbox with the application's mapper and contracts, then reads the stored
     * record back as a consumer does.
     *
     * @return the stored JSON
     */
    private static JsonNode assertStoredAndReadBack(AfterSalesEventEnvelope envelope) throws Exception {
        OutboxEventRepository repository = Mockito.mock(OutboxEventRepository.class);
        EventContracts contracts = EventTestSupport.contracts();

        outbox(repository).enqueueEvent("after-sales-events", String.valueOf(envelope.data().orderId()), envelope);

        ArgumentCaptor<OutboxEventEntity> captor = ArgumentCaptor.forClass(OutboxEventEntity.class);
        Mockito.verify(repository).save(captor.capture());
        OutboxEventEntity row = captor.getValue();
        Assertions.assertEquals("AFTER_SALES_CASE", row.aggregateType());
        Assertions.assertEquals(envelope.data().caseId(), row.aggregateId());
        Assertions.assertEquals("after-sales-events", row.topic());
        Assertions.assertEquals("1001", row.eventKey());
        Assertions.assertEquals(envelope.eventType(), row.eventType());
        Assertions.assertEquals(1L, row.aggregateSequence());
        Assertions.assertEquals(
                envelope.withSequence(1L),
                contracts.read(EventTopic.AFTER_SALES_EVENTS, row.payload(), AfterSalesEventEnvelope.class)
        );
        JsonNode stored = EventTestSupport.mapper().readTree(row.payload());
        // Snowflake order numbers exceed 2^53: a string, as in the REST API.
        Assertions.assertTrue(stored.get("data").get("order_no").isString(), row.payload());
        return stored;
    }


    private static void assertViolation(String expected, String eventType, AfterSalesCasePayload data) {
        OutboxEventRepository repository = Mockito.mock(OutboxEventRepository.class);

        EventSchemaViolationException violation = Assertions.assertThrows(
                EventSchemaViolationException.class,
                () -> outbox(repository).enqueueEvent(
                        "after-sales-events", "1001", AfterSalesEvents.envelope(eventType, data, NOW, null))
        );

        Assertions.assertTrue(violation.getMessage().contains(expected), violation.getMessage());
        Mockito.verify(repository, Mockito.never()).save(Mockito.any());
    }


    /**
     * The outbox with the application's mapper and event contracts, and an in-memory sequence counter per aggregate.
     */
    private static OrderEventOutboxService outbox(OutboxEventRepository repository) {
        Map<String, Long> sequences = new HashMap<>();
        return new OrderEventOutboxService(
                20,
                Duration.ofSeconds(30),
                2,
                "order-events",
                EventTestSupport.mapper(),
                repository,
                (aggregateType, aggregateId) -> sequences.merge(aggregateType + ":" + aggregateId, 1L, Long::sum),
                EventTestSupport.contracts()
        );
    }


    private static AfterSalesCasePayload openedRequest() {
        return AfterSalesEvents.payload(
                new CaseBuilder().build(), CaseBuilder.LINES, ORDER_NO, "OPEN", AfterSalesActor.CUSTOMER, CUSTOMER_ID);
    }


    /**
     * The case as the single writer leaves it after {@code trigger} (the "fields written" column of ADR 0002 table
     * 5.2): from a request of 16.09 USD, through a merchant rejection or a counter-offer of 5.50 where the step needs
     * one.
     */
    private static AfterSalesCaseEntity caseAfter(AfterSalesTrigger trigger) {
        CaseBuilder rejected = new CaseBuilder().version(2L).merchant("REJECTED", "The fries were in the bag.");
        CaseBuilder offered = new CaseBuilder().version(2L).merchant("OFFERED", "We can refund the fries.")
                .offered("5.50");
        LocalDateTime customerDeadline = NOW.plusHours(48);
        return switch (trigger) {
            case MERCHANT_APPROVE -> new CaseBuilder().version(1L).merchant("APPROVED", null)
                    .refunding("16.09", "MERCHANT_APPROVED").build();
            case MERCHANT_REJECT -> rejected.version(1L).status(AfterSalesStatus.REJECTED).deadline(customerDeadline)
                    .build();
            case MERCHANT_OFFER -> offered.version(1L).status(AfterSalesStatus.COUNTER_OFFERED)
                    .deadline(customerDeadline).build();
            case MERCHANT_TIMEOUT -> new CaseBuilder().version(1L).refunding("16.09", "MERCHANT_TIMEOUT").build();
            case CUSTOMER_ACCEPT_OFFER -> offered.refunding("5.50", "OFFER_ACCEPTED").build();
            case CUSTOMER_DECLINE_OFFER -> offered.status(AfterSalesStatus.REJECTED).deadline(customerDeadline)
                    .build();
            case OFFER_TIMEOUT -> offered.refunding("5.50", "OFFER_TIMEOUT").build();
            case CUSTOMER_ESCALATE -> rejected.status(AfterSalesStatus.IN_ARBITRATION).deadline(NOW.plusHours(72))
                    .build();
            case CUSTOMER_TIMEOUT -> rejected.closed("CUSTOMER_TIMEOUT").build();
            case CUSTOMER_WITHDRAW -> new CaseBuilder().version(1L).closed("WITHDRAWN").build();
            case ADMIN_APPROVE_FULL -> rejected.version(3L).refunding("16.09", "ARBITRATION_FULL").build();
            case ADMIN_APPROVE_PARTIAL -> rejected.version(3L).refunding("6.00", "ARBITRATION_PARTIAL").build();
            case ADMIN_REJECT -> rejected.version(3L).closed("ARBITRATION_REJECTED").build();
            case ARBITRATION_TIMEOUT -> rejected.version(3L).refunding("16.09", "ARBITRATION_TIMEOUT").build();
            case REFUND_SUCCEEDED -> new CaseBuilder().version(2L).merchant("APPROVED", null)
                    .refunding("16.09", "MERCHANT_APPROVED").status(AfterSalesStatus.REFUNDED).refunded("16.09")
                    .build();
        };
    }


    /**
     * An after-sales case of an order of 16.09 USD (two lines), by default the customer's request for all of it just
     * opened in PENDING_MERCHANT.
     */
    private static final class CaseBuilder {

        static final List<AfterSalesCaseLineEntity> LINES = List.of(
                new AfterSalesCaseLineEntity(
                        9L, 501L, 41L, "Whopper Meal", new BigDecimal("10.59"), 1, new BigDecimal("10.59"), "USD"),
                new AfterSalesCaseLineEntity(
                        10L, 501L, 42L, "Large Fries", new BigDecimal("2.75"), 2, new BigDecimal("5.50"), "USD")
        );

        private String kind = "CUSTOMER_REQUEST";
        private AfterSalesStatus status = AfterSalesStatus.PENDING_MERCHANT;
        private String reasonCode = "MISSING_ITEM";
        private String description = "The fries were missing.";
        private BigDecimal offered;
        private BigDecimal approved;
        private BigDecimal refunded;
        private String currency = "USD";
        private String merchantDecision;
        private String merchantNote;
        private String resolution;
        private String closeReason;
        private LocalDateTime deadline = NOW.plusHours(24);
        private Long version = 0L;


        CaseBuilder kind(String kind) {
            this.kind = kind;
            return this;
        }


        CaseBuilder status(AfterSalesStatus status) {
            this.status = status;
            return this;
        }


        CaseBuilder request(String reasonCode, String description) {
            this.reasonCode = reasonCode;
            this.description = description;
            return this;
        }


        CaseBuilder version(Long version) {
            this.version = version;
            return this;
        }


        CaseBuilder merchant(String decision, String note) {
            this.merchantDecision = decision;
            this.merchantNote = note;
            return this;
        }


        CaseBuilder offered(String amount) {
            this.offered = new BigDecimal(amount);
            return this;
        }


        CaseBuilder approved(String amount) {
            this.approved = new BigDecimal(amount);
            return this;
        }


        CaseBuilder refunded(String amount) {
            this.refunded = new BigDecimal(amount);
            return this;
        }


        CaseBuilder resolution(String resolution) {
            this.resolution = resolution;
            return this;
        }


        CaseBuilder currency(String currency) {
            this.currency = currency;
            return this;
        }


        CaseBuilder deadline(LocalDateTime deadline) {
            this.deadline = deadline;
            return this;
        }


        /**
         * REFUNDING with {@code approved} and {@code resolution}; no deadline.
         */
        CaseBuilder refunding(String approved, String resolution) {
            return status(AfterSalesStatus.REFUNDING).approved(approved).resolution(resolution).deadline(null);
        }


        CaseBuilder closed(String closeReason) {
            this.closeReason = closeReason;
            return status(AfterSalesStatus.CLOSED).deadline(null);
        }


        AfterSalesCaseEntity build() {
            return new AfterSalesCaseEntity(
                    501L,
                    1001L,
                    CUSTOMER_ID,
                    1L,
                    kind,
                    1,
                    status.name(),
                    reasonCode,
                    description,
                    new BigDecimal("16.09"),
                    new BigDecimal("16.09"),
                    offered,
                    approved,
                    refunded,
                    currency,
                    merchantDecision,
                    merchantNote,
                    resolution,
                    closeReason,
                    null,
                    deadline,
                    null,
                    0,
                    version,
                    NOW.minusHours(2),
                    NOW
            );
        }
    }
}
