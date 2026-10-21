package com.laioffer.onlineorder.aftersales;


import com.laioffer.onlineorder.exception.BadRequestException;
import com.laioffer.onlineorder.exception.ConflictException;
import com.laioffer.onlineorder.exception.ResourceNotFoundException;
import com.laioffer.onlineorder.model.OrderActor;
import com.laioffer.onlineorder.fulfilment.order.OrderLifecycleCommands;
import com.laioffer.onlineorder.fulfilment.order.OrderLifecycleCommands.AfterSalesDecision;
import com.laioffer.onlineorder.fulfilment.order.OrderLifecycleCommands.AfterSalesResponse;
import com.laioffer.onlineorder.messaging.AfterSalesCasePayload;
import com.laioffer.onlineorder.money.Money;
import com.laioffer.onlineorder.repository.OrderRepository;
import com.laioffer.onlineorder.statemachine.Decision;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;


import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;


/**
 * The merchant's answers on the after-sales side of the seam (ADR 0002 sections 7 and 11.3): the shape of an answer is
 * checked before any lock, the case is locked scoped to the restaurant (404 otherwise, and for the automatic refund of
 * a cancelled order), the single writer fires the merchant's trigger with the MERCHANT actor, and the case comes back as
 * an event-shaped snapshot with whether this command changed it. The machine's own rules are
 * {@link AfterSalesStateMachineTests}'; the whole path on a database is the merchant module's integration tests'.
 */
@ExtendWith(MockitoExtension.class)
class AfterSalesMerchantDecisionsTests {

    private static final long CASE_ID = 300L;
    private static final long ORDER_ID = 30L;
    private static final long ORDER_NO = 9030L;
    private static final long RESTAURANT_ID = 7L;
    private static final long MERCHANT_ID = 40L;
    private static final OrderActor MERCHANT = OrderActor.merchant(MERCHANT_ID);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 20, 12, 0);
    private static final LocalDateTime DEADLINE = NOW.plusHours(20);

    @Mock
    private AfterSalesCaseRepository caseRepository;

    @Mock
    private AfterSalesCaseLineRepository lineRepository;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private AfterSalesTransitions transitions;

    private AfterSalesMerchantDecisions decisions;


    @BeforeEach
    void setup() {
        decisions = new AfterSalesMerchantDecisions(caseRepository, lineRepository, orderRepository, transitions);
    }


    @Test
    void approve_shouldLockTheRestaurantsCaseFireTheMerchantsTriggerAndReturnTheCaseAsItsEventsCarryIt() {
        AfterSalesCaseEntity pending = pending("USD");
        AfterSalesCaseEntity refunding = moved(pending, "REFUNDING", null, new BigDecimal("16.09"), "APPROVED", null);
        Mockito.when(caseRepository.lockByIdAndRestaurantId(CASE_ID, RESTAURANT_ID)).thenReturn(pending);
        Mockito.when(transitions.fire(Mockito.eq(pending), Mockito.any())).thenReturn(applied(
                AfterSalesTrigger.MERCHANT_APPROVE, AfterSalesStatus.REFUNDING, refunding));
        stubLinesAndOrderNo();

        AfterSalesDecision decision = decisions.decide(CASE_ID, RESTAURANT_ID, AfterSalesResponse.approve(), MERCHANT);

        Assertions.assertTrue(decision.changed());
        AfterSalesCasePayload snapshot = decision.snapshot();
        Assertions.assertEquals(CASE_ID, snapshot.caseId());
        Assertions.assertEquals(ORDER_NO, snapshot.orderNo());
        Assertions.assertEquals(RESTAURANT_ID, snapshot.restaurantId());
        Assertions.assertEquals("REFUNDING", snapshot.status());
        Assertions.assertEquals(1L, snapshot.version());
        Assertions.assertEquals("MERCHANT_APPROVE", snapshot.action());
        Assertions.assertEquals("MERCHANT", snapshot.actorType());
        Assertions.assertEquals(MERCHANT_ID, snapshot.actorId());
        Assertions.assertEquals(new BigDecimal("16.09"), snapshot.approvedAmount());
        Assertions.assertEquals("APPROVED", snapshot.merchantDecision());
        Assertions.assertEquals(1, snapshot.lines().size());
        Assertions.assertEquals("Whopper Meal", snapshot.lines().get(0).menuItemName());
        Assertions.assertEquals("USD", snapshot.lines().get(0).currency());

        InOrder steps = Mockito.inOrder(caseRepository, transitions);
        steps.verify(caseRepository).lockByIdAndRestaurantId(CASE_ID, RESTAURANT_ID);
        steps.verify(transitions).fire(pending, new AfterSalesTransitions.Command(
                AfterSalesTrigger.MERCHANT_APPROVE, AfterSalesActor.MERCHANT, MERCHANT_ID, null, null, null));
        Mockito.verify(lineRepository).findAllByCaseIdIn(List.of(CASE_ID));
        Mockito.verifyNoMoreInteractions(caseRepository, transitions);
    }


    @Test
    void reject_shouldFireWithTheTrimmedReason() {
        AfterSalesCaseEntity pending = pending("USD");
        Mockito.when(caseRepository.lockByIdAndRestaurantId(CASE_ID, RESTAURANT_ID)).thenReturn(pending);
        Mockito.when(transitions.fire(Mockito.eq(pending), Mockito.any())).thenReturn(applied(
                AfterSalesTrigger.MERCHANT_REJECT,
                AfterSalesStatus.REJECTED,
                moved(pending, "REJECTED", null, null, "REJECTED", "Nothing was missing.")
        ));
        stubLinesAndOrderNo();

        AfterSalesDecision decision = decisions.decide(
                CASE_ID, RESTAURANT_ID, AfterSalesResponse.reject("  Nothing was missing.  "), MERCHANT);

        Assertions.assertEquals("REJECTED", decision.snapshot().status());
        Assertions.assertEquals("Nothing was missing.", decision.snapshot().merchantNote());
        Mockito.verify(transitions).fire(pending, new AfterSalesTransitions.Command(
                AfterSalesTrigger.MERCHANT_REJECT, AfterSalesActor.MERCHANT, MERCHANT_ID, null,
                "Nothing was missing.", null));
    }


    @Test
    void offer_shouldFireWithTheAmountInTheCasesCurrency() {
        AfterSalesCaseEntity pending = pending("USD");
        Mockito.when(caseRepository.lockByIdAndRestaurantId(CASE_ID, RESTAURANT_ID)).thenReturn(pending);
        Mockito.when(transitions.fire(Mockito.eq(pending), Mockito.any())).thenReturn(applied(
                AfterSalesTrigger.MERCHANT_OFFER,
                AfterSalesStatus.COUNTER_OFFERED,
                moved(pending, "COUNTER_OFFERED", new BigDecimal("5.00"), null, "OFFERED", null)
        ));
        stubLinesAndOrderNo();

        AfterSalesDecision decision = decisions.decide(
                CASE_ID, RESTAURANT_ID, AfterSalesResponse.offer(new BigDecimal("5.0"), " "), MERCHANT);

        Assertions.assertEquals(new BigDecimal("5.00"), decision.snapshot().offeredAmount());
        ArgumentCaptor<AfterSalesTransitions.Command> command =
                ArgumentCaptor.forClass(AfterSalesTransitions.Command.class);
        Mockito.verify(transitions).fire(Mockito.eq(pending), command.capture());
        Assertions.assertEquals(AfterSalesTrigger.MERCHANT_OFFER, command.getValue().trigger());
        Assertions.assertEquals(Money.of("5.00", "USD"), command.getValue().amount());
        Assertions.assertNull(command.getValue().note(), "a blank note is no note");
    }


    @Test
    void aRepeatedAnswer_shouldReturnTheCaseUnchanged() {
        AfterSalesCaseEntity offered = moved(pending("USD"), "COUNTER_OFFERED", new BigDecimal("5.00"), null,
                "OFFERED", null);
        Mockito.when(caseRepository.lockByIdAndRestaurantId(CASE_ID, RESTAURANT_ID)).thenReturn(offered);
        Mockito.when(transitions.fire(Mockito.eq(offered), Mockito.any())).thenReturn(new AfterSalesTransitions.Step(
                new Decision.AlreadyDone<>(AfterSalesTrigger.MERCHANT_OFFER, AfterSalesStatus.COUNTER_OFFERED),
                offered
        ));
        stubLinesAndOrderNo();

        AfterSalesDecision decision = decisions.decide(
                CASE_ID, RESTAURANT_ID, AfterSalesResponse.offer(new BigDecimal("5"), null), MERCHANT);

        Assertions.assertFalse(decision.changed());
        Assertions.assertEquals("COUNTER_OFFERED", decision.snapshot().status());
        Assertions.assertEquals(1L, decision.snapshot().version());
    }


    @Test
    void anAnswerTheCaseNoLongerWaitsFor_shouldBeAStatusConflict() {
        AfterSalesCaseEntity refunding = moved(pending("USD"), "REFUNDING", null, new BigDecimal("16.09"), null,
                null);
        Mockito.when(caseRepository.lockByIdAndRestaurantId(CASE_ID, RESTAURANT_ID)).thenReturn(refunding);
        Mockito.when(transitions.fire(Mockito.eq(refunding), Mockito.any())).thenReturn(new AfterSalesTransitions.Step(
                new Decision.Rejected<>(AfterSalesTrigger.MERCHANT_REJECT, AfterSalesStatus.REFUNDING,
                        Decision.Reason.NOT_FROM_CURRENT_STATE, null),
                refunding
        ));

        ConflictException refused = Assertions.assertThrows(ConflictException.class, () -> decisions.decide(
                CASE_ID, RESTAURANT_ID, AfterSalesResponse.reject("Too late"), MERCHANT));

        Assertions.assertEquals(OrderLifecycleCommands.AFTER_SALES_STATUS_CONFLICT, refused.getCode());
        Assertions.assertEquals(
                "After-sales case is REFUNDING; only PENDING_MERCHANT cases can be rejected", refused.getMessage());
        Mockito.verifyNoInteractions(lineRepository, orderRepository);
    }


    @Test
    void anAnswerFromTheDeadlineOn_shouldBeRefusedAsTooLate() {
        AfterSalesCaseEntity pending = pending("USD");
        Mockito.when(caseRepository.lockByIdAndRestaurantId(CASE_ID, RESTAURANT_ID)).thenReturn(pending);
        Mockito.when(transitions.fire(Mockito.eq(pending), Mockito.any())).thenReturn(new AfterSalesTransitions.Step(
                new Decision.Rejected<>(AfterSalesTrigger.MERCHANT_APPROVE, AfterSalesStatus.PENDING_MERCHANT,
                        Decision.Reason.GUARD_FAILED, AfterSalesStateMachine.BEFORE_DEADLINE),
                pending
        ));

        ConflictException refused = Assertions.assertThrows(ConflictException.class, () -> decisions.decide(
                CASE_ID, RESTAURANT_ID, AfterSalesResponse.approve(), MERCHANT));

        Assertions.assertEquals(OrderLifecycleCommands.AFTER_SALES_DEADLINE_PASSED, refused.getCode());
        Assertions.assertEquals(
                "The deadline of this after-sales step passed at " + AfterSalesErrors.timestamp(DEADLINE),
                refused.getMessage()
        );
    }


    @Test
    void aMissingCaseAnotherRestaurantsCaseAndAnAutomaticRefund_shouldNotExistForTheMerchant() {
        Mockito.when(caseRepository.lockByIdAndRestaurantId(CASE_ID, RESTAURANT_ID)).thenReturn(null);
        ResourceNotFoundException missing = Assertions.assertThrows(ResourceNotFoundException.class,
                () -> decisions.decide(CASE_ID, RESTAURANT_ID, AfterSalesResponse.approve(), MERCHANT));
        Assertions.assertEquals("After-sales case not found", missing.getMessage());

        AfterSalesCaseEntity automatic = new AfterSalesCaseEntity(CASE_ID, ORDER_ID, 3L, RESTAURANT_ID,
                "ORDER_CANCELLED", 1, "REFUNDING", null, null, new BigDecimal("16.09"), new BigDecimal("16.09"), null,
                new BigDecimal("16.09"), null, "USD", null, null, "ORDER_CANCELLED", null, null, null, null, 0, 0L,
                NOW, NOW);
        Mockito.when(caseRepository.lockByIdAndRestaurantId(CASE_ID, RESTAURANT_ID)).thenReturn(automatic);
        Assertions.assertThrows(ResourceNotFoundException.class,
                () -> decisions.decide(CASE_ID, RESTAURANT_ID, AfterSalesResponse.approve(), MERCHANT));

        // A merchant without a restaurant reaches no case at all.
        Assertions.assertThrows(ResourceNotFoundException.class,
                () -> decisions.decide(CASE_ID, null, AfterSalesResponse.approve(), MERCHANT));
        Mockito.verify(caseRepository, Mockito.times(2)).lockByIdAndRestaurantId(CASE_ID, RESTAURANT_ID);
        Mockito.verifyNoMoreInteractions(caseRepository);
        Mockito.verifyNoInteractions(transitions);
    }


    @Test
    void aMalformedAnswer_shouldBeABadRequestBeforeAnyLock() {
        Assertions.assertEquals(AfterSalesMerchantDecisions.REASON_REQUIRED, Assertions.assertThrows(
                BadRequestException.class,
                () -> decisions.decide(CASE_ID, RESTAURANT_ID, AfterSalesResponse.reject("   "), MERCHANT)
        ).getMessage());
        Assertions.assertEquals(AfterSalesMerchantDecisions.REASON_REQUIRED, Assertions.assertThrows(
                BadRequestException.class,
                () -> decisions.decide(CASE_ID, RESTAURANT_ID, AfterSalesResponse.reject(null), MERCHANT)
        ).getMessage());
        Assertions.assertEquals(AfterSalesMerchantDecisions.AMOUNT_REQUIRED, Assertions.assertThrows(
                BadRequestException.class,
                () -> decisions.decide(CASE_ID, RESTAURANT_ID, AfterSalesResponse.offer(null, "Half"), MERCHANT)
        ).getMessage());
        Assertions.assertEquals(AfterSalesMerchantDecisions.AMOUNT_NOT_EXPECTED, Assertions.assertThrows(
                BadRequestException.class,
                () -> decisions.decide(CASE_ID, RESTAURANT_ID, new AfterSalesResponse(
                        AfterSalesResponse.Type.APPROVE, BigDecimal.ONE, null), MERCHANT)
        ).getMessage());

        Mockito.verifyNoInteractions(caseRepository, transitions);
    }


    @Test
    void anOfferWithMoreDecimalPlacesThanTheCurrencyHas_shouldBeABadRequestOfItsOwn() {
        Mockito.when(caseRepository.lockByIdAndRestaurantId(CASE_ID, RESTAURANT_ID)).thenReturn(pending("USD"));
        Assertions.assertEquals("Amount must have at most 2 decimal places", Assertions.assertThrows(
                BadRequestException.class, () -> decisions.decide(
                        CASE_ID, RESTAURANT_ID, AfterSalesResponse.offer(new BigDecimal("5.001"), null), MERCHANT)
        ).getMessage());
        Assertions.assertEquals("The amount must be more than 0 and less than the requested 16.09 USD",
                Assertions.assertThrows(BadRequestException.class, () -> decisions.decide(
                        CASE_ID, RESTAURANT_ID, AfterSalesResponse.offer(new BigDecimal("1e12"), null), MERCHANT)
                ).getMessage());

        Mockito.when(caseRepository.lockByIdAndRestaurantId(CASE_ID, RESTAURANT_ID)).thenReturn(pending("JPY"));
        Assertions.assertEquals("Amount must have at most 0 decimal places", Assertions.assertThrows(
                BadRequestException.class, () -> decisions.decide(
                        CASE_ID, RESTAURANT_ID, AfterSalesResponse.offer(new BigDecimal("500.50"), null), MERCHANT)
        ).getMessage());

        Mockito.verifyNoInteractions(transitions);
    }


    @Test
    void aRider_shouldHaveNoAfterSalesRole() {
        Mockito.when(caseRepository.lockByIdAndRestaurantId(CASE_ID, RESTAURANT_ID)).thenReturn(pending("USD"));

        Assertions.assertThrows(IllegalStateException.class, () -> decisions.decide(
                CASE_ID, RESTAURANT_ID, AfterSalesResponse.approve(), OrderActor.rider(4L)));

        Mockito.verifyNoInteractions(transitions);
    }


    @Test
    void theSeamsErrorCodes_shouldBeTheAfterSalesApis() {
        Assertions.assertEquals(
                AfterSalesErrors.AFTER_SALES_STATUS_CONFLICT, OrderLifecycleCommands.AFTER_SALES_STATUS_CONFLICT);
        Assertions.assertEquals(
                AfterSalesErrors.AFTER_SALES_DEADLINE_PASSED, OrderLifecycleCommands.AFTER_SALES_DEADLINE_PASSED);
        Assertions.assertEquals(AfterSalesCustomerService.CASE_NOT_FOUND, "After-sales case not found");
    }


    private void stubLinesAndOrderNo() {
        Mockito.when(lineRepository.findAllByCaseIdIn(List.of(CASE_ID))).thenReturn(List.of(new AfterSalesCaseLineEntity(
                900L, CASE_ID, 77L, "Whopper Meal", new BigDecimal("16.09"), 1, new BigDecimal("16.09"), "USD")));
        Mockito.when(orderRepository.findOrderNoById(ORDER_ID)).thenReturn(Optional.of(ORDER_NO));
    }


    private static AfterSalesTransitions.Step applied(
            AfterSalesTrigger trigger,
            AfterSalesStatus to,
            AfterSalesCaseEntity moved
    ) {
        return new AfterSalesTransitions.Step(
                new Decision.Apply<>(trigger, AfterSalesStatus.PENDING_MERCHANT, to), moved);
    }


    private static AfterSalesCaseEntity pending(String currency) {
        BigDecimal requested = "JPY".equals(currency) ? new BigDecimal("1600") : new BigDecimal("16.09");
        return new AfterSalesCaseEntity(CASE_ID, ORDER_ID, 3L, RESTAURANT_ID, "CUSTOMER_REQUEST", 1,
                "PENDING_MERCHANT", "MISSING_ITEM", "The fries were missing.", requested, requested, null, null, null,
                currency, null, null, null, null, null, DEADLINE, null, 0, 0L, NOW.minusHours(4), NOW.minusHours(4));
    }


    private static AfterSalesCaseEntity moved(
            AfterSalesCaseEntity from,
            String status,
            BigDecimal offered,
            BigDecimal approved,
            String merchantDecision,
            String merchantNote
    ) {
        boolean open = "COUNTER_OFFERED".equals(status) || "REJECTED".equals(status);
        return new AfterSalesCaseEntity(from.id(), from.orderId(), from.customerId(), from.restaurantId(), from.kind(),
                from.attemptNo(), status, from.reasonCode(), from.description(), from.orderTotal(),
                from.requestedAmount(), offered, approved, null, from.currency(), merchantDecision, merchantNote,
                approved == null ? null : "MERCHANT_APPROVED", null, null, open ? NOW.plusHours(48) : null, null, 0,
                from.version() + 1, from.createdAt(), NOW);
    }
}
