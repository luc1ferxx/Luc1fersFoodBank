package com.laioffer.onlineorder.fulfilment.notification;


import com.laioffer.onlineorder.aftersales.AfterSalesActor;
import com.laioffer.onlineorder.aftersales.AfterSalesCloseReason;
import com.laioffer.onlineorder.aftersales.AfterSalesKind;
import com.laioffer.onlineorder.aftersales.AfterSalesResolution;
import com.laioffer.onlineorder.aftersales.AfterSalesStatus;
import com.laioffer.onlineorder.aftersales.AfterSalesTransitions;
import com.laioffer.onlineorder.aftersales.AfterSalesTrigger;
import com.laioffer.onlineorder.fulfilment.FulfilmentEvents;
import com.laioffer.onlineorder.messaging.AfterSalesCaseLinePayload;
import com.laioffer.onlineorder.messaging.AfterSalesCasePayload;
import com.laioffer.onlineorder.messaging.AfterSalesEventEnvelope;
import com.laioffer.onlineorder.repository.OrderNotificationRepository;
import com.laioffer.onlineorder.repository.OrderPaymentRepository;
import com.laioffer.onlineorder.repository.OrderRepository;
import com.laioffer.onlineorder.service.OrderNotificationService;
import com.laioffer.onlineorder.service.ProcessedEventService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;


import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;


/**
 * The customer notifications of the after-sales events (ADR 0002 section 10.3, P3-10): one text per event type, with
 * the amounts in their currency and the order's public number; one notification per order, event type and case, so a
 * redelivered event notifies once while the second request of an order notifies again; and an automatic refund of a
 * cancelled order (ORDER_CANCELLED) notifies only when it is refunded. The real {@link OrderNotificationService}
 * writes the texts; the repositories and the processed_events marks are mocks.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AfterSalesNotificationServiceTests {

    private static final Long ORDER_ID = 1001L;

    private static final Long ORDER_NO = 7_254_189_912_938_504_192L;

    private static final String ORDER = "order #7254189912938504192";

    private static final Long CUSTOMER_ID = 3L;

    private static final LocalDateTime OPENED_AT = LocalDateTime.of(2026, 4, 25, 13, 10, 5, 120_394_000);

    /**
     * Shown to the minute, truncated: "2026-04-27 15:42".
     */
    private static final LocalDateTime DEADLINE = LocalDateTime.of(2026, 4, 27, 15, 42, 59, 900_000_000);

    @Mock
    private OrderNotificationRepository orderNotificationRepository;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderPaymentRepository orderPaymentRepository;

    @Mock
    private ProcessedEventService processedEventService;

    private AfterSalesNotificationService service;


    @BeforeEach
    void setUp() {
        service = new AfterSalesNotificationService(
                new OrderNotificationService(orderNotificationRepository, orderRepository, orderPaymentRepository),
                processedEventService
        );
        Mockito.when(processedEventService.markIfUnseen(
                Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(true);
    }


    @Test
    void caseOpened_ofACustomerRequest_shouldConfirmItWithTheAmountAndTheRestaurantsDeadline() {
        service.handle(request().envelope("evt-opened", FulfilmentEvents.AFTER_SALES_CASE_OPENED));

        assertNotified(FulfilmentEvents.AFTER_SALES_CASE_OPENED, "case:501", "Refund requested",
                "Your refund request of 10.59 USD for " + ORDER + " was sent to the restaurant. If it does not answer by "
                        + "2026-04-27 15:42, the request is approved automatically.");
        Mockito.verify(processedEventService).markIfUnseen(
                AfterSalesNotificationService.AFTER_SALES_NOTIFICATION_CONSUMER,
                "evt-opened",
                "evt-opened",
                FulfilmentEvents.AFTER_SALES_CASE_OPENED,
                "AFTER_SALES_CASE",
                501L
        );
        // The payload carries the order number: no lookup.
        Mockito.verifyNoInteractions(orderRepository, orderPaymentRepository);
    }


    @Test
    void offerMade_shouldShowTheOfferInTheOrdersCurrencyWithTheNoteAndTheReplyDeadline() {
        service.handle(request()
                .currency("EUR")
                .requested("12.40")
                .status(AfterSalesStatus.COUNTER_OFFERED)
                .action(AfterSalesTrigger.MERCHANT_OFFER, AfterSalesActor.MERCHANT)
                .offered("6.20")
                .merchantNote("Only the fries were cold")
                .envelope("evt-offer", FulfilmentEvents.AFTER_SALES_OFFER_MADE));

        assertNotified(FulfilmentEvents.AFTER_SALES_OFFER_MADE, "case:501", "Partial refund offered",
                "The restaurant offers 6.20 EUR instead of the 12.40 EUR you requested for " + ORDER
                        + ": Only the fries were cold. Reply by 2026-04-27 15:42, or the offer is accepted automatically.");
    }


    @Test
    void requestRejected_byTheRestaurant_shouldPassOnItsReasonAndOfferTheEscalation() {
        service.handle(request()
                .status(AfterSalesStatus.REJECTED)
                .action(AfterSalesTrigger.MERCHANT_REJECT, AfterSalesActor.MERCHANT)
                .merchantNote("The burger was in the bag at pickup.")
                .envelope("evt-rejected", FulfilmentEvents.AFTER_SALES_REQUEST_REJECTED));
        service.handle(request()
                .caseId(502L)
                .status(AfterSalesStatus.REJECTED)
                .action(AfterSalesTrigger.MERCHANT_REJECT, AfterSalesActor.MERCHANT)
                .merchantNote("  ")
                .envelope("evt-rejected-without-note", FulfilmentEvents.AFTER_SALES_REQUEST_REJECTED));

        assertNotified(FulfilmentEvents.AFTER_SALES_REQUEST_REJECTED, "case:501", "Refund request declined",
                "The restaurant declined your refund request for " + ORDER + ": The burger was in the bag at pickup. "
                        + "You can ask the platform to step in by 2026-04-27 15:42.");
        assertNotified(FulfilmentEvents.AFTER_SALES_REQUEST_REJECTED, "case:502", "Refund request declined",
                "The restaurant declined your refund request for " + ORDER + ". "
                        + "You can ask the platform to step in by 2026-04-27 15:42.");
    }


    @Test
    void requestRejected_becauseTheCustomerDeclinedTheOffer_shouldOfferTheEscalation() {
        service.handle(request()
                .status(AfterSalesStatus.REJECTED)
                .action(AfterSalesTrigger.CUSTOMER_DECLINE_OFFER, AfterSalesActor.CUSTOMER)
                .offered("5.00")
                .merchantNote("Only the fries were cold")
                .envelope("evt-declined", FulfilmentEvents.AFTER_SALES_REQUEST_REJECTED));

        assertNotified(FulfilmentEvents.AFTER_SALES_REQUEST_REJECTED, "case:501", "Offer declined",
                "You declined the restaurant's offer for " + ORDER + ". "
                        + "You can ask the platform to step in by 2026-04-27 15:42.");
    }


    @Test
    void caseEscalated_shouldSayThePlatformDecidesByItsDeadline() {
        service.handle(request()
                .status(AfterSalesStatus.IN_ARBITRATION)
                .action(AfterSalesTrigger.CUSTOMER_ESCALATE, AfterSalesActor.CUSTOMER)
                .envelope("evt-escalated", FulfilmentEvents.AFTER_SALES_CASE_ESCALATED));

        assertNotified(FulfilmentEvents.AFTER_SALES_CASE_ESCALATED, "case:501", "Platform review requested",
                "The platform will review your refund request of 10.59 USD for " + ORDER + ". If it has not decided by "
                        + "2026-04-27 15:42, the request is approved automatically.");
    }


    /**
     * Every way a customer's request reaches REFUNDING says how its approved amount was set; ORDER_CANCELLED is the
     * resolution of the automatic refund, which opens in REFUNDING and never sends refund_approved.
     */
    @ParameterizedTest
    @EnumSource(value = AfterSalesResolution.class, mode = EnumSource.Mode.EXCLUDE, names = "ORDER_CANCELLED")
    void refundApproved_shouldShowTheApprovedAmountAndHowItWasSet(AfterSalesResolution resolution) {
        Map<AfterSalesResolution, String> leads = new EnumMap<>(AfterSalesResolution.class);
        leads.put(AfterSalesResolution.MERCHANT_APPROVED, "The restaurant approved your refund request: ");
        leads.put(AfterSalesResolution.MERCHANT_TIMEOUT,
                "The restaurant did not answer in time, so your refund request was approved: ");
        leads.put(AfterSalesResolution.OFFER_ACCEPTED, "You accepted the restaurant's offer: ");
        leads.put(AfterSalesResolution.OFFER_TIMEOUT,
                "You did not answer the restaurant's offer in time, so it was accepted: ");
        leads.put(AfterSalesResolution.ARBITRATION_FULL, "The platform approved your refund request: ");
        leads.put(AfterSalesResolution.ARBITRATION_PARTIAL, "The platform approved part of your refund request: ");
        leads.put(AfterSalesResolution.ARBITRATION_TIMEOUT,
                "The platform did not decide in time, so your refund request was approved: ");

        service.handle(request()
                .status(AfterSalesStatus.REFUNDING)
                .resolution(resolution)
                .approved("7.25")
                .deadlineAt(null)
                .envelope("evt-approved-" + resolution, FulfilmentEvents.AFTER_SALES_REFUND_APPROVED));

        assertNotified(FulfilmentEvents.AFTER_SALES_REFUND_APPROVED, "case:501", "Refund approved",
                leads.get(resolution) + "7.25 USD for " + ORDER + " will be refunded.");
    }


    @Test
    void caseRefunded_ofACustomerRequest_shouldShowTheRefundedAmount() {
        service.handle(request()
                .status(AfterSalesStatus.REFUNDED)
                .action(AfterSalesTrigger.REFUND_SUCCEEDED, AfterSalesActor.SYSTEM)
                .resolution(AfterSalesResolution.OFFER_ACCEPTED)
                .approved("5.00")
                .refunded("5.00")
                .deadlineAt(null)
                .envelope("evt-refunded", FulfilmentEvents.AFTER_SALES_CASE_REFUNDED));

        assertNotified(FulfilmentEvents.AFTER_SALES_CASE_REFUNDED, "case:501", "Refund completed",
                "5.00 USD for " + ORDER + " was refunded.");
    }


    @ParameterizedTest
    @EnumSource(AfterSalesCloseReason.class)
    void caseClosed_shouldSayWhyByItsReason(AfterSalesCloseReason closeReason) {
        Map<AfterSalesCloseReason, String> messages = new EnumMap<>(AfterSalesCloseReason.class);
        messages.put(AfterSalesCloseReason.WITHDRAWN, "You withdrew your refund request for " + ORDER + ".");
        messages.put(AfterSalesCloseReason.CUSTOMER_TIMEOUT, "Your refund request for " + ORDER
                + " was closed because the platform was not asked to step in before the deadline.");
        messages.put(AfterSalesCloseReason.ARBITRATION_REJECTED,
                "The platform rejected your refund request for " + ORDER + ".");

        service.handle(request()
                .status(AfterSalesStatus.CLOSED)
                .closeReason(closeReason)
                .deadlineAt(null)
                .envelope("evt-closed-" + closeReason, FulfilmentEvents.AFTER_SALES_CASE_CLOSED));

        assertNotified(FulfilmentEvents.AFTER_SALES_CASE_CLOSED, "case:501", "Refund request closed",
                messages.get(closeReason));
    }


    /**
     * The automatic refund of a cancelled order: its opening adds nothing (the cancellation notice already promised
     * the refund), its completion tells the customer the payment came back.
     */
    @Test
    void orderCancelledCase_shouldNotifyOnlyWhenItIsRefunded() {
        CaseEvent cancelled = request()
                .kind(AfterSalesKind.ORDER_CANCELLED)
                .requested("22.00")
                .approved("22.00")
                .resolution(AfterSalesResolution.ORDER_CANCELLED)
                .status(AfterSalesStatus.REFUNDING)
                .action(AfterSalesTransitions.ORDER_CANCELLED_ACTION, AfterSalesActor.SYSTEM)
                .deadlineAt(null);

        service.handle(cancelled.envelope("evt-cancelled-opened", FulfilmentEvents.AFTER_SALES_CASE_OPENED));
        Mockito.verifyNoInteractions(orderNotificationRepository);

        service.handle(cancelled
                .status(AfterSalesStatus.REFUNDED)
                .action(AfterSalesTrigger.REFUND_SUCCEEDED, AfterSalesActor.SYSTEM)
                .refunded("22.00")
                .envelope("evt-cancelled-refunded", FulfilmentEvents.AFTER_SALES_CASE_REFUNDED));

        assertNotified(FulfilmentEvents.AFTER_SALES_CASE_REFUNDED, "case:501", "Refund completed",
                "Your payment of 22.00 USD for cancelled " + ORDER + " was refunded.");
        Mockito.verifyNoMoreInteractions(orderNotificationRepository);
    }


    @Test
    void redeliveredEvent_shouldBeMarkedOnceAndNotifyOnce() {
        Mockito.when(processedEventService.markIfUnseen(
                Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(true, false);
        AfterSalesEventEnvelope opened = request().envelope("evt-redelivered", FulfilmentEvents.AFTER_SALES_CASE_OPENED);

        service.handle(opened);
        service.handle(opened);

        Mockito.verify(processedEventService, Mockito.times(2)).markIfUnseen(
                AfterSalesNotificationService.AFTER_SALES_NOTIFICATION_CONSUMER,
                "evt-redelivered",
                "evt-redelivered",
                FulfilmentEvents.AFTER_SALES_CASE_OPENED,
                "AFTER_SALES_CASE",
                501L
        );
        Mockito.verify(orderNotificationRepository, Mockito.times(1)).insertIfAbsentInScope(
                Mockito.anyLong(), Mockito.anyLong(), Mockito.anyString(), Mockito.anyString(), Mockito.anyString(),
                Mockito.anyString(), Mockito.any(LocalDateTime.class));
    }


    /**
     * ADR 0002 section 20: an order's second request (after withdrawing the first) sends the same event types as the
     * first; each case writes in its own dedup scope, so both requests are notified.
     */
    @Test
    void secondRequestOfAnOrder_shouldNotifyInItsOwnScope() {
        service.handle(request().envelope("evt-first-opened", FulfilmentEvents.AFTER_SALES_CASE_OPENED));
        service.handle(request()
                .status(AfterSalesStatus.CLOSED)
                .action(AfterSalesTrigger.CUSTOMER_WITHDRAW, AfterSalesActor.CUSTOMER)
                .closeReason(AfterSalesCloseReason.WITHDRAWN)
                .deadlineAt(null)
                .envelope("evt-first-withdrawn", FulfilmentEvents.AFTER_SALES_CASE_CLOSED));
        service.handle(request()
                .caseId(502L)
                .attemptNo(2)
                .requested("4.00")
                .envelope("evt-second-opened", FulfilmentEvents.AFTER_SALES_CASE_OPENED));

        ArgumentCaptor<String> eventTypes = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> scopes = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> messages = ArgumentCaptor.forClass(String.class);
        Mockito.verify(orderNotificationRepository, Mockito.times(3)).insertIfAbsentInScope(
                Mockito.eq(ORDER_ID), Mockito.eq(CUSTOMER_ID), eventTypes.capture(), scopes.capture(),
                Mockito.anyString(), messages.capture(), Mockito.any(LocalDateTime.class));
        Assertions.assertEquals(List.of(
                FulfilmentEvents.AFTER_SALES_CASE_OPENED,
                FulfilmentEvents.AFTER_SALES_CASE_CLOSED,
                FulfilmentEvents.AFTER_SALES_CASE_OPENED
        ), eventTypes.getAllValues());
        Assertions.assertEquals(List.of("case:501", "case:501", "case:502"), scopes.getAllValues());
        Assertions.assertTrue(messages.getAllValues().get(2).startsWith("Your refund request of 4.00 USD for " + ORDER),
                messages.getAllValues().get(2));
    }


    /**
     * The payload carries the order number of every order that has one; without it the message falls back to the
     * order module's lookup, and from there to the internal id.
     */
    @Test
    void eventWithoutAnOrderNumber_shouldNameTheOrderByItsLookup() {
        service.handle(request().orderNo(null).envelope("evt-no-number", FulfilmentEvents.AFTER_SALES_CASE_OPENED));

        Mockito.verify(orderRepository).findOrderNoById(ORDER_ID);
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        Mockito.verify(orderNotificationRepository).insertIfAbsentInScope(
                Mockito.eq(ORDER_ID), Mockito.eq(CUSTOMER_ID), Mockito.anyString(), Mockito.eq("case:501"),
                Mockito.anyString(), message.capture(), Mockito.any(LocalDateTime.class));
        Assertions.assertTrue(message.getValue().contains("for order #1001 "), message.getValue());
    }


    @Test
    void eventWithoutItsIdentifiers_shouldBeRefusedWithoutANotification() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> service.handle(
                request().envelope("  ", FulfilmentEvents.AFTER_SALES_CASE_OPENED)));
        Mockito.verifyNoInteractions(processedEventService);

        Assertions.assertThrows(IllegalArgumentException.class, () -> service.handle(
                request().caseId(null).envelope("evt-no-case", FulfilmentEvents.AFTER_SALES_CASE_OPENED)));
        Assertions.assertThrows(IllegalArgumentException.class, () -> service.handle(
                request().customerId(null).envelope("evt-no-customer", FulfilmentEvents.AFTER_SALES_CASE_OPENED)));
        Mockito.verifyNoInteractions(orderNotificationRepository);
    }


    private void assertNotified(String eventType, String scope, String title, String message) {
        Mockito.verify(orderNotificationRepository).insertIfAbsentInScope(
                Mockito.eq(ORDER_ID),
                Mockito.eq(CUSTOMER_ID),
                Mockito.eq(eventType),
                Mockito.eq(scope),
                Mockito.eq(title),
                Mockito.eq(message),
                Mockito.any(LocalDateTime.class)
        );
    }


    /**
     * A customer's request 501 of order 1001, attempt 1, 10.59 USD requested, waiting for the restaurant until
     * {@link #DEADLINE}; each test moves it to the step its event reports.
     */
    private static CaseEvent request() {
        return new CaseEvent();
    }


    private static final class CaseEvent {

        private Long caseId = 501L;
        private Long orderNo = ORDER_NO;
        private Long customerId = CUSTOMER_ID;
        private String kind = AfterSalesKind.CUSTOMER_REQUEST.name();
        private Integer attemptNo = 1;
        private String status = AfterSalesStatus.PENDING_MERCHANT.name();
        private String action = AfterSalesTransitions.OPEN_ACTION;
        private String actorType = AfterSalesActor.CUSTOMER.name();
        private BigDecimal requested = new BigDecimal("10.59");
        private BigDecimal offered;
        private BigDecimal approved;
        private BigDecimal refunded;
        private String currency = "USD";
        private String merchantNote;
        private String resolution;
        private String closeReason;
        private LocalDateTime deadlineAt = DEADLINE;


        CaseEvent caseId(Long value) {
            caseId = value;
            return this;
        }


        CaseEvent orderNo(Long value) {
            orderNo = value;
            return this;
        }


        CaseEvent customerId(Long value) {
            customerId = value;
            return this;
        }


        CaseEvent kind(AfterSalesKind value) {
            kind = value.name();
            return this;
        }


        CaseEvent attemptNo(int value) {
            attemptNo = value;
            return this;
        }


        CaseEvent status(AfterSalesStatus value) {
            status = value.name();
            return this;
        }


        CaseEvent action(AfterSalesTrigger trigger, AfterSalesActor actor) {
            return action(trigger.name(), actor);
        }


        CaseEvent action(String value, AfterSalesActor actor) {
            action = value;
            actorType = actor.name();
            return this;
        }


        CaseEvent requested(String value) {
            requested = new BigDecimal(value);
            return this;
        }


        CaseEvent offered(String value) {
            offered = new BigDecimal(value);
            return this;
        }


        CaseEvent approved(String value) {
            approved = new BigDecimal(value);
            return this;
        }


        CaseEvent refunded(String value) {
            refunded = new BigDecimal(value);
            return this;
        }


        CaseEvent currency(String value) {
            currency = value;
            return this;
        }


        CaseEvent merchantNote(String value) {
            merchantNote = value;
            return this;
        }


        CaseEvent resolution(AfterSalesResolution value) {
            resolution = value.name();
            return this;
        }


        CaseEvent closeReason(AfterSalesCloseReason value) {
            closeReason = value.name();
            return this;
        }


        CaseEvent deadlineAt(LocalDateTime value) {
            deadlineAt = value;
            return this;
        }


        AfterSalesEventEnvelope envelope(String eventId, String eventType) {
            AfterSalesCasePayload data = new AfterSalesCasePayload(
                    caseId,
                    ORDER_ID,
                    orderNo,
                    customerId,
                    1L,
                    kind,
                    attemptNo,
                    status,
                    action,
                    actorType,
                    null,
                    1L,
                    "MISSING_ITEM",
                    "The burger was missing from the bag.",
                    requested,
                    offered,
                    approved,
                    refunded,
                    currency,
                    null,
                    merchantNote,
                    resolution,
                    closeReason,
                    deadlineAt,
                    List.of(new AfterSalesCaseLinePayload(41L, "Whopper Meal", 1, requested, requested, currency)),
                    OPENED_AT
            );
            return new AfterSalesEventEnvelope(
                    eventId, 1, eventType, "AFTER_SALES_CASE", caseId, 2L, OPENED_AT.plusHours(1), eventId, null, data);
        }
    }
}
