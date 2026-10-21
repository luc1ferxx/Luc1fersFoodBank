package com.laioffer.onlineorder.aftersales;


import com.laioffer.onlineorder.entity.OrderEntity;
import com.laioffer.onlineorder.entity.OrderHistoryItemEntity;
import com.laioffer.onlineorder.entity.OrderPaymentEntity;
import com.laioffer.onlineorder.exception.BadRequestException;
import com.laioffer.onlineorder.exception.ConflictException;
import com.laioffer.onlineorder.exception.ResourceNotFoundException;
import com.laioffer.onlineorder.money.Money;
import com.laioffer.onlineorder.repository.OrderHistoryItemRepository;
import com.laioffer.onlineorder.repository.OrderPaymentRepository;
import com.laioffer.onlineorder.repository.OrderRepository;
import com.laioffer.onlineorder.statemachine.Decision;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.jdbc.repository.query.Query;


import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;


/**
 * The customer's after-sales service (ADR 0002 sections 11.2, 19, 20 and 27) on mocks: the shape checks of a request
 * before anything is read, every eligibility rule under the order's lock with the time read after it, the attempt
 * rules with the status a withdrawn first request was withdrawn from, the lines and money rules, the mapping of the
 * attempt key's violation, the commands on a locked case with the mapping of a refused step, and the read-only
 * queries.
 */
@ExtendWith(MockitoExtension.class)
class AfterSalesCustomerServiceTests {

    static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 21, 12, 0, 30);

    static final Long CUSTOMER_ID = 3L;

    static final Long ORDER_ID = 1001L;

    static final Long ORDER_NO = 7_254_189_912_938_504_192L;

    static final Long CASE_ID = 501L;

    private static final OrderEntity ORDER = completedAt(NOW.minusHours(2));

    @Mock
    private AfterSalesCaseRepository caseRepository;

    @Mock
    private AfterSalesCaseHistoryRepository historyRepository;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderHistoryItemRepository orderHistoryItemRepository;

    @Mock
    private OrderPaymentRepository orderPaymentRepository;

    @Mock
    private AfterSalesTransitions transitions;

    @Mock
    private IdempotentRequests idempotentRequests;

    @Mock
    private AfterSalesCaseViews views;

    @Mock
    private Supplier<LocalDateTime> clock;

    private AfterSalesCustomerService service;


    @BeforeEach
    void setup() {
        service = new AfterSalesCustomerService(
                caseRepository,
                historyRepository,
                orderRepository,
                orderHistoryItemRepository,
                orderPaymentRepository,
                transitions,
                idempotentRequests,
                views,
                new AfterSalesDeadlines(Duration.ofHours(24), Duration.ofHours(24), Duration.ofHours(48),
                        Duration.ofHours(72)),
                clock
        );
    }


    // ---- open ----


    @Test
    void open_wholeOrder_shouldRequestEveryLineInFullUnderTheOrdersLock() {
        AfterSalesCaseDto answer = eligibleOrderOpens(order(), List.of());

        AfterSalesCaseDto result = service.open(CUSTOMER_ID, ORDER_ID, wholeOrder(), " open-1 ");

        Assertions.assertSame(answer, result);
        InOrder lockThenClock = Mockito.inOrder(idempotentRequests, orderRepository, clock, transitions);
        lockThenClock.verify(idempotentRequests).run(Mockito.eq(CUSTOMER_ID), Mockito.eq("after-sales-open:v1:1001"),
                Mockito.eq("open-1"), Mockito.anyString(), Mockito.any());
        lockThenClock.verify(orderRepository).lockByIdAndCustomerId(ORDER_ID, CUSTOMER_ID);
        lockThenClock.verify(clock).get();
        lockThenClock.verify(transitions).open(Mockito.any());
        AfterSalesTransitions.CustomerRequest request = openedRequest();
        Assertions.assertSame(order(), request.order(), "the locked order");
        Assertions.assertEquals(1, request.attemptNo());
        Assertions.assertEquals(AfterSalesReason.MISSING_ITEM, request.reason());
        Assertions.assertEquals("The fries were missing.", request.description());
        Assertions.assertEquals(List.of(
                new AfterSalesCaseLineEntity(null, null, 41L, "Whopper Meal", new BigDecimal("10.59"), 1,
                        new BigDecimal("10.59"), "USD"),
                new AfterSalesCaseLineEntity(null, null, 42L, "Large Fries", new BigDecimal("2.75"), 2,
                        new BigDecimal("5.50"), "USD")
        ), request.lines());
        Assertions.assertEquals(Money.of("16.09", "USD"), request.requested());
        Assertions.assertEquals(NOW, request.now());
        Assertions.assertEquals("open-1", request.idempotencyKey());
    }


    @Test
    void open_items_shouldRequestTheLinesGivenWithinTheQuantitiesOrdered() {
        eligibleOrderOpens(order(), List.of());

        service.open(CUSTOMER_ID, ORDER_ID, items(new OpenAfterSalesLine(42L, 1)), "open-1");

        AfterSalesTransitions.CustomerRequest request = openedRequest();
        Assertions.assertEquals(List.of(new AfterSalesCaseLineEntity(null, null, 42L, "Large Fries",
                new BigDecimal("2.75"), 1, new BigDecimal("2.75"), "USD")), request.lines());
        Assertions.assertEquals(Money.of("2.75", "USD"), request.requested());
        Assertions.assertEquals(AfterSalesReason.QUALITY_ISSUE, request.reason());
    }


    @Test
    void open_shouldHashTheRequestWithItsLinesInIdOrder() {
        Mockito.when(idempotentRequests.run(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(CASE_ID);
        stubDetail(caseRow(AfterSalesStatus.PENDING_MERCHANT));

        service.open(CUSTOMER_ID, ORDER_ID, items(new OpenAfterSalesLine(42L, 1), new OpenAfterSalesLine(41L, 1)), "k");
        service.open(CUSTOMER_ID, ORDER_ID, items(new OpenAfterSalesLine(41L, 1), new OpenAfterSalesLine(42L, 1)), "k");
        service.open(CUSTOMER_ID, ORDER_ID, items(new OpenAfterSalesLine(41L, 1), new OpenAfterSalesLine(42L, 2)), "k");
        service.open(CUSTOMER_ID, ORDER_ID, new OpenAfterSalesBody("ITEMS", List.of(new OpenAfterSalesLine(41L, 1),
                new OpenAfterSalesLine(42L, 1)), "QUALITY_ISSUE", "It was warm."), "k");
        service.open(CUSTOMER_ID, ORDER_ID, wholeOrder(), "k");

        ArgumentCaptor<String> hashes = ArgumentCaptor.forClass(String.class);
        Mockito.verify(idempotentRequests, Mockito.times(5)).run(Mockito.eq(CUSTOMER_ID),
                Mockito.eq("after-sales-open:v1:1001"), Mockito.eq("k"), hashes.capture(), Mockito.any());
        List<String> values = hashes.getAllValues();
        Assertions.assertEquals(values.get(0), values.get(1), "the order of the lines does not matter");
        Assertions.assertEquals(5, values.stream().distinct().count() + 1);
        Mockito.verifyNoInteractions(orderRepository, transitions);
    }


    static Stream<Arguments> invalidBodies() {
        return Stream.of(
                Arguments.of(new OpenAfterSalesBody("PARTIAL", null, "OTHER", "x"),
                        "Scope must be WHOLE_ORDER or ITEMS"),
                Arguments.of(new OpenAfterSalesBody("WHOLE_ORDER", List.of(new OpenAfterSalesLine(41L, 1)), "OTHER",
                        "x"), "A WHOLE_ORDER request takes no lines"),
                Arguments.of(new OpenAfterSalesBody("ITEMS", null, "OTHER", "x"),
                        "An ITEMS request needs at least one line"),
                Arguments.of(new OpenAfterSalesBody("ITEMS", List.of(), "OTHER", "x"),
                        "An ITEMS request needs at least one line"),
                Arguments.of(new OpenAfterSalesBody("ITEMS", List.of(new OpenAfterSalesLine(42L, 1),
                        new OpenAfterSalesLine(41L, 1), new OpenAfterSalesLine(42L, 2)), "OTHER", "x"),
                        "Order item 42 is listed more than once"),
                Arguments.of(new OpenAfterSalesBody("WHOLE_ORDER", null, "BAD_MOOD", "x"),
                        "Unsupported reason code: BAD_MOOD")
        );
    }


    @ParameterizedTest
    @MethodSource("invalidBodies")
    void open_withAnInvalidBody_shouldBeABadRequestBeforeAnythingIsRead(OpenAfterSalesBody body, String message) {
        BadRequestException refused = Assertions.assertThrows(BadRequestException.class,
                () -> service.open(CUSTOMER_ID, ORDER_ID, body, "open-1"));

        Assertions.assertEquals(message, refused.getMessage());
        Mockito.verifyNoInteractions(idempotentRequests, orderRepository, transitions);
    }


    @Test
    void open_withoutAKey_shouldBeABadRequestBeforeAnythingIsRead() {
        BadRequestException refused = Assertions.assertThrows(BadRequestException.class,
                () -> service.open(CUSTOMER_ID, ORDER_ID, wholeOrder(), " "));

        Assertions.assertEquals("Idempotency-Key header is required", refused.getMessage());
        Mockito.verifyNoInteractions(idempotentRequests, orderRepository, transitions);
    }


    @Test
    void open_ofAMissingOrAnotherCustomersOrder_shouldBeNotFound() {
        runRequestsInline();

        ResourceNotFoundException missing = Assertions.assertThrows(ResourceNotFoundException.class,
                () -> service.open(CUSTOMER_ID, ORDER_ID, wholeOrder(), "open-1"));

        Assertions.assertEquals("Order not found", missing.getMessage());
        Mockito.verify(orderRepository).lockByIdAndCustomerId(ORDER_ID, CUSTOMER_ID);
        Mockito.verifyNoInteractions(clock, transitions);
    }


    static Stream<Arguments> ineligibleOrders() {
        OrderEntity order = order();
        return Stream.of(
                Arguments.of(withStatus(order, "PAID"), true,
                        "Only COMPLETED orders can get after-sales; this order is PAID"),
                Arguments.of(withStatus(order, "CANCELLED"), true,
                        "Only COMPLETED orders can get after-sales; this order is CANCELLED"),
                Arguments.of(new OrderEntity(ORDER_ID, ORDER_NO, CUSTOMER_ID, order.totalPrice(), "COMPLETED",
                        order.createdAt(), null, null, order.updatedAt(), "USD"), true, "This order has no restaurant"),
                Arguments.of(order, false, "Order has no captured payment"),
                Arguments.of(completedAt(NOW.minusHours(24).minusSeconds(1)), true,
                        "The after-sales window of this order closed at 2026-10-21T12:00:29 UTC")
        );
    }


    @ParameterizedTest
    @MethodSource("ineligibleOrders")
    void open_ofAnIneligibleOrder_shouldBeNotEligible(OrderEntity order, boolean paid, String message) {
        runRequestsInline();
        Mockito.when(orderRepository.lockByIdAndCustomerId(ORDER_ID, CUSTOMER_ID)).thenReturn(order);
        Mockito.when(clock.get()).thenReturn(NOW);
        if (order.restaurantId() != null && "COMPLETED".equals(order.status())) {
            Mockito.when(orderPaymentRepository.findByOrderId(ORDER_ID))
                    .thenReturn(paid ? Optional.of(payment("16.09")) : Optional.empty());
        }

        ConflictException refused = Assertions.assertThrows(ConflictException.class,
                () -> service.open(CUSTOMER_ID, ORDER_ID, wholeOrder(), "open-1"));

        Assertions.assertEquals("AFTER_SALES_NOT_ELIGIBLE", refused.getCode());
        Assertions.assertEquals(message, refused.getMessage());
        Mockito.verifyNoInteractions(transitions);
    }


    @Test
    void open_atTheLastMomentOfTheWindow_shouldStillOpen() {
        eligibleOrderOpens(completedAt(NOW.minusHours(24)), List.of());

        service.open(CUSTOMER_ID, ORDER_ID, wholeOrder(), "open-1");

        Assertions.assertEquals(1, openedRequest().attemptNo());
    }


    /**
     * A clock with nanoseconds (Linux) is read in microseconds, what the database keeps of the case's times.
     */
    @Test
    void open_atAClockWithNanoseconds_shouldTakeTheTimeInMicroseconds() {
        eligibleOrderOpens(order(), List.of());
        Mockito.when(clock.get()).thenReturn(NOW.withNano(123_456_789));

        service.open(CUSTOMER_ID, ORDER_ID, wholeOrder(), "open-1");

        Assertions.assertEquals(NOW.withNano(123_456_000), openedRequest().now());
    }


    static Stream<Arguments> earlierCases() {
        AfterSalesCaseEntity withdrawn = closedRequest(1, AfterSalesCloseReason.WITHDRAWN);
        return Stream.of(
                Arguments.of(List.of(caseRow(AfterSalesStatus.PENDING_MERCHANT)), "ALREADY_OPEN"),
                Arguments.of(List.of(caseRow(AfterSalesStatus.IN_ARBITRATION)), "ALREADY_OPEN"),
                Arguments.of(List.of(caseRow(AfterSalesStatus.REFUNDING)), "ALREADY_OPEN"),
                Arguments.of(List.of(closedRequest(1, AfterSalesCloseReason.CUSTOMER_TIMEOUT)), "NO_ATTEMPT_LEFT"),
                Arguments.of(List.of(closedRequest(1, AfterSalesCloseReason.ARBITRATION_REJECTED)), "NO_ATTEMPT_LEFT"),
                Arguments.of(List.of(caseRow(AfterSalesStatus.REFUNDED)), "NO_ATTEMPT_LEFT"),
                Arguments.of(List.of(withdrawn, closedRequest(2, AfterSalesCloseReason.WITHDRAWN)), "NO_ATTEMPT_LEFT"),
                Arguments.of(List.of(withdrawn, caseRow(AfterSalesStatus.COUNTER_OFFERED)), "ALREADY_OPEN")
        );
    }


    /**
     * Section 20: a first request when the order has none, a second only after the first was withdrawn (from which
     * status: the next test); an open one, every other ending and a second request refuse another, without reading
     * any history.
     */
    @ParameterizedTest
    @MethodSource("earlierCases")
    void open_shouldBeTheFirstRequestOrTheSecondAfterAWithdrawal(List<AfterSalesCaseEntity> cases, String outcome) {
        stubEligibleOrder(order(), payment("16.09"), cases);

        ConflictException refused = Assertions.assertThrows(ConflictException.class,
                () -> service.open(CUSTOMER_ID, ORDER_ID, wholeOrder(), "open-2"));

        Assertions.assertEquals("AFTER_SALES_NOT_ELIGIBLE", refused.getCode());
        Assertions.assertEquals(outcome.equals("ALREADY_OPEN")
                ? "An after-sales case is already open for this order"
                : "This order cannot be submitted for after-sales again", refused.getMessage());
        Mockito.verifyNoInteractions(transitions, orderHistoryItemRepository, historyRepository);
    }


    static Stream<Arguments> withdrawalOrigins() {
        return Stream.of(
                Arguments.of("while waiting for the merchant", withdrawnRequest(null, null), "PENDING_MERCHANT",
                        true),
                Arguments.of("on a counter-offer", withdrawnRequest("OFFERED", "8.00"), "COUNTER_OFFERED", true),
                Arguments.of("after the merchant rejected it", withdrawnRequest("REJECTED", null), "REJECTED",
                        false),
                Arguments.of("after the customer declined the offer", withdrawnRequest("OFFERED", "8.00"),
                        "REJECTED", false),
                Arguments.of("in arbitration", withdrawnRequest("REJECTED", null), "IN_ARBITRATION", false),
                Arguments.of("without its withdrawal step", withdrawnRequest(null, null), null, false)
        );
    }


    /**
     * Section 27 (follow-up 24): the second request needs the first withdrawn while PENDING_MERCHANT or
     * COUNTER_OFFERED, read from its CUSTOMER_WITHDRAW step after the order's lock. Withdrawing a rejection (the
     * merchant's, or after declining the offer) or an arbitration refuses it with the same 409 as every other ending,
     * so the customer cannot restart the merchant's clock and its full refund on timeout.
     */
    @ParameterizedTest(name = "withdrawn {0}")
    @MethodSource("withdrawalOrigins")
    void open_afterAWithdrawnFirstRequest_shouldBeTheSecondOnlyIfWithdrawnBeforeARejection(
            String origin,
            AfterSalesCaseEntity withdrawn,
            String withdrawnFrom,
            boolean secondAllowed
    ) {
        Mockito.when(historyRepository.findWithdrawnFromStatus(withdrawn.id()))
                .thenReturn(Optional.ofNullable(withdrawnFrom));
        if (secondAllowed) {
            eligibleOrderOpens(order(), List.of(withdrawn));

            service.open(CUSTOMER_ID, ORDER_ID, wholeOrder(), "open-2");

            Assertions.assertEquals(2, openedRequest().attemptNo(), origin);
            InOrder lockThenHistory = Mockito.inOrder(orderRepository, caseRepository, historyRepository, transitions);
            lockThenHistory.verify(orderRepository).lockByIdAndCustomerId(ORDER_ID, CUSTOMER_ID);
            lockThenHistory.verify(caseRepository).findAllByOrderId(ORDER_ID);
            lockThenHistory.verify(historyRepository).findWithdrawnFromStatus(withdrawn.id());
            lockThenHistory.verify(transitions).open(Mockito.any());
            return;
        }
        stubEligibleOrder(order(), payment("16.09"), List.of(withdrawn));

        ConflictException refused = Assertions.assertThrows(ConflictException.class,
                () -> service.open(CUSTOMER_ID, ORDER_ID, wholeOrder(), "open-2"));

        Assertions.assertEquals("AFTER_SALES_NOT_ELIGIBLE", refused.getCode(), origin);
        Assertions.assertEquals("This order cannot be submitted for after-sales again", refused.getMessage(), origin);
        Mockito.verify(historyRepository).findWithdrawnFromStatus(withdrawn.id());
        Mockito.verifyNoInteractions(transitions, orderHistoryItemRepository);
    }


    /**
     * The eligible list filters in SQL (OrderRepository.findPageOpenToAfterSales), so it is pinned to the statuses
     * the open command allows a second request after: the UI never offers a request the command would refuse.
     */
    @Test
    void eligibleOrders_shouldListAnOrderAgainAfterTheSameWithdrawalsAsTheOpenCommand() throws Exception {
        String sql = OrderRepository.class
                .getMethod("findPageOpenToAfterSales", Long.class, LocalDateTime.class, int.class)
                .getAnnotation(Query.class)
                .value();
        Matcher withdrawnFrom = Pattern.compile("after_sales_case_history\\.action = 'CUSTOMER_WITHDRAW'\\s+"
                + "AND after_sales_case_history\\.from_status IN \\(([^)]*)\\)").matcher(sql);

        Assertions.assertTrue(withdrawnFrom.find(), sql);
        Set<AfterSalesStatus> listed = Arrays.stream(withdrawnFrom.group(1).split(","))
                .map(status -> AfterSalesStatus.valueOf(status.trim().replace("'", "")))
                .collect(Collectors.toSet());
        Assertions.assertEquals(AfterSalesCustomerService.REQUEST_AGAIN_AFTER_WITHDRAWAL_FROM, listed);
        Assertions.assertEquals(Set.of(AfterSalesStatus.PENDING_MERCHANT, AfterSalesStatus.COUNTER_OFFERED), listed);
        Assertions.assertTrue(sql.contains("after_sales_cases.close_reason = 'WITHDRAWN'"), sql);
        Assertions.assertTrue(sql.contains("after_sales_cases.attempt_no = 1"), sql);
    }


    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "99|1|Order item 99 is not part of this order",
            "42|3|Order item 42 was ordered 2 times; at most that many can be refunded"
    })
    void open_withALineOutsideTheOrder_shouldBeABadRequest(Long orderItemId, Integer quantity, String message) {
        stubEligibleOrder(order(), payment("16.09"), List.of());
        Mockito.when(orderHistoryItemRepository.findAllByOrderIdOrderByIdAsc(ORDER_ID)).thenReturn(items());

        BadRequestException refused = Assertions.assertThrows(BadRequestException.class, () -> service.open(
                CUSTOMER_ID, ORDER_ID, items(new OpenAfterSalesLine(41L, 1), new OpenAfterSalesLine(orderItemId,
                        quantity)), "open-1"));

        Assertions.assertEquals(message, refused.getMessage());
        Mockito.verifyNoInteractions(transitions);
    }


    @Test
    void open_ofNothingButFreeItems_shouldBeABadRequestBeforeAnyInsert() {
        stubEligibleOrder(order(), payment("16.09"), List.of());
        Mockito.when(orderHistoryItemRepository.findAllByOrderIdOrderByIdAsc(ORDER_ID)).thenReturn(List.of(
                new OrderHistoryItemEntity(43L, ORDER_ID, 9L, 7L, new BigDecimal("0.00"), 3, "Napkins", null, null,
                        "USD")));

        BadRequestException refused = Assertions.assertThrows(BadRequestException.class,
                () -> service.open(CUSTOMER_ID, ORDER_ID, wholeOrder(), "open-1"));

        Assertions.assertEquals("Refund amount must be greater than zero", refused.getMessage());
        Mockito.verifyNoInteractions(transitions);
    }


    @Test
    void open_aboveWhatIsLeftOfTheCapturedPayment_shouldBeNotEligible() {
        stubEligibleOrder(order(), payment("10.00"), List.of());
        Mockito.when(orderHistoryItemRepository.findAllByOrderIdOrderByIdAsc(ORDER_ID)).thenReturn(items());

        ConflictException refused = Assertions.assertThrows(ConflictException.class,
                () -> service.open(CUSTOMER_ID, ORDER_ID, wholeOrder(), "open-1"));

        Assertions.assertEquals("AFTER_SALES_NOT_ELIGIBLE", refused.getCode());
        Assertions.assertEquals(
                "A refund of 16.09 USD would exceed the 10.00 USD left of the order's captured payment",
                refused.getMessage()
        );
        Mockito.verifyNoInteractions(transitions);
    }


    /**
     * Whatever the order already had approved counts against its payment, even an approval no request of the customer
     * made (the automatic refund of a cancelled order, which a completed order cannot have).
     */
    @Test
    void open_shouldCountEverythingAlreadyApprovedForTheOrder() {
        AfterSalesCaseEntity automatic = new AfterSalesCaseEntity(600L, ORDER_ID, CUSTOMER_ID, 7L, "ORDER_CANCELLED", 1,
                "REFUNDING", null, null, new BigDecimal("16.09"), new BigDecimal("16.09"), null,
                new BigDecimal("6.09"), null, "USD", null, null, "ORDER_CANCELLED", null, null, null, null, 0, 1L,
                NOW.minusHours(1), NOW.minusHours(1));
        stubEligibleOrder(order(), payment("16.09"), List.of(automatic));
        Mockito.when(orderHistoryItemRepository.findAllByOrderIdOrderByIdAsc(ORDER_ID)).thenReturn(items());

        ConflictException refused = Assertions.assertThrows(ConflictException.class,
                () -> service.open(CUSTOMER_ID, ORDER_ID, wholeOrder(), "open-1"));

        Assertions.assertEquals(
                "A refund of 16.09 USD would exceed the 10.00 USD left of the order's captured payment",
                refused.getMessage()
        );
    }


    @Test
    void open_whenTheAttemptKeyRefusesTheInsert_shouldBeNotEligible() {
        stubEligibleOrder(order(), payment("16.09"), List.of());
        Mockito.when(orderHistoryItemRepository.findAllByOrderIdOrderByIdAsc(ORDER_ID)).thenReturn(items());
        Mockito.when(transitions.open(Mockito.any())).thenThrow(new DuplicateKeyException(
                "Unique index or primary key violation: \"PUBLIC.UQ_AFTER_SALES_CASE_ATTEMPT\""));

        ConflictException refused = Assertions.assertThrows(ConflictException.class,
                () -> service.open(CUSTOMER_ID, ORDER_ID, wholeOrder(), "open-1"));

        Assertions.assertEquals("AFTER_SALES_NOT_ELIGIBLE", refused.getCode());
        Assertions.assertEquals("An after-sales case is already open for this order", refused.getMessage());
    }


    @Test
    void open_whenAnotherUniqueKeyRefusesTheInsert_shouldPassItOn() {
        stubEligibleOrder(order(), payment("16.09"), List.of());
        Mockito.when(orderHistoryItemRepository.findAllByOrderIdOrderByIdAsc(ORDER_ID)).thenReturn(items());
        DuplicateKeyException other = new DuplicateKeyException("duplicate key value violates \"uq_after_sales_line\"");
        DuplicateKeyException anonymous = new DuplicateKeyException(null);
        Mockito.when(transitions.open(Mockito.any())).thenThrow(other).thenThrow(anonymous);

        Assertions.assertSame(other, Assertions.assertThrows(DuplicateKeyException.class,
                () -> service.open(CUSTOMER_ID, ORDER_ID, wholeOrder(), "open-1")));
        Assertions.assertSame(anonymous, Assertions.assertThrows(DuplicateKeyException.class,
                () -> service.open(CUSTOMER_ID, ORDER_ID, wholeOrder(), "open-1")));
    }


    // ---- commands ----


    @ParameterizedTest
    @CsvSource({
            "accept-offer,CUSTOMER_ACCEPT_OFFER",
            "decline-offer,CUSTOMER_DECLINE_OFFER",
            "escalate,CUSTOMER_ESCALATE",
            "withdraw,CUSTOMER_WITHDRAW"
    })
    void commands_shouldFireTheirTriggerOnTheCustomersLockedCase(String route, AfterSalesTrigger trigger) {
        runRequestsInline();
        AfterSalesCaseEntity locked = caseRow(AfterSalesStatus.COUNTER_OFFERED);
        AfterSalesCaseEntity moved = caseRow(AfterSalesStatus.CLOSED);
        Mockito.when(caseRepository.lockByIdAndCustomerId(CASE_ID, CUSTOMER_ID)).thenReturn(locked);
        Mockito.when(transitions.fire(Mockito.eq(locked), Mockito.any())).thenReturn(new AfterSalesTransitions.Step(
                new Decision.Apply<>(trigger, AfterSalesStatus.COUNTER_OFFERED, AfterSalesStatus.CLOSED), moved));
        AfterSalesCaseDto answer = stubDetail(moved);

        AfterSalesCaseDto result = command(route, " key-1 ");

        Assertions.assertSame(answer, result);
        Mockito.verify(idempotentRequests).run(Mockito.eq(CUSTOMER_ID), Mockito.eq("after-sales-" + route + ":v1:501"),
                Mockito.eq("key-1"), Mockito.anyString(), Mockito.any());
        Mockito.verify(transitions).fire(locked, new AfterSalesTransitions.Command(
                trigger, AfterSalesActor.CUSTOMER, CUSTOMER_ID, null, null, "key-1"));
    }


    @Test
    void command_onAMissingOrAnotherCustomersCase_shouldBeNotFound() {
        runRequestsInline();

        ResourceNotFoundException missing = Assertions.assertThrows(ResourceNotFoundException.class,
                () -> service.withdraw(CUSTOMER_ID, CASE_ID, "key-1"));

        Assertions.assertEquals("After-sales case not found", missing.getMessage());
        Mockito.verifyNoInteractions(transitions);
    }


    @Test
    void command_thatAlreadyHappened_shouldAnswerWithTheCaseUnchanged() {
        runRequestsInline();
        AfterSalesCaseEntity withdrawn = closedRequest(1, AfterSalesCloseReason.WITHDRAWN);
        Mockito.when(caseRepository.lockByIdAndCustomerId(CASE_ID, CUSTOMER_ID)).thenReturn(withdrawn);
        Mockito.when(transitions.fire(Mockito.eq(withdrawn), Mockito.any())).thenReturn(new AfterSalesTransitions.Step(
                new Decision.AlreadyDone<>(AfterSalesTrigger.CUSTOMER_WITHDRAW, AfterSalesStatus.CLOSED), withdrawn));
        AfterSalesCaseDto answer = stubDetail(withdrawn);

        Assertions.assertSame(answer, service.withdraw(CUSTOMER_ID, CASE_ID, "key-2"));
    }


    @Test
    void command_refusedByTheStatusOrTheDeadline_shouldBeAConflict() {
        runRequestsInline();
        AfterSalesCaseEntity pending = caseRow(AfterSalesStatus.PENDING_MERCHANT);
        Mockito.when(caseRepository.lockByIdAndCustomerId(CASE_ID, CUSTOMER_ID)).thenReturn(pending);
        Mockito.when(transitions.fire(Mockito.eq(pending), Mockito.any()))
                .thenReturn(new AfterSalesTransitions.Step(new Decision.Rejected<>(
                        AfterSalesTrigger.CUSTOMER_ACCEPT_OFFER, AfterSalesStatus.PENDING_MERCHANT,
                        Decision.Reason.NOT_FROM_CURRENT_STATE, null), pending))
                .thenReturn(new AfterSalesTransitions.Step(new Decision.Rejected<>(
                        AfterSalesTrigger.CUSTOMER_ACCEPT_OFFER, AfterSalesStatus.PENDING_MERCHANT,
                        Decision.Reason.GUARD_FAILED, AfterSalesStateMachine.BEFORE_DEADLINE), pending));

        ConflictException status = Assertions.assertThrows(ConflictException.class,
                () -> service.acceptOffer(CUSTOMER_ID, CASE_ID, "key-1"));
        ConflictException deadline = Assertions.assertThrows(ConflictException.class,
                () -> service.acceptOffer(CUSTOMER_ID, CASE_ID, "key-2"));

        Assertions.assertEquals("AFTER_SALES_STATUS_CONFLICT", status.getCode());
        Assertions.assertEquals("After-sales case is PENDING_MERCHANT; only COUNTER_OFFERED cases can be accepted",
                status.getMessage());
        Assertions.assertEquals("AFTER_SALES_DEADLINE_PASSED", deadline.getCode());
        Assertions.assertEquals("The deadline of this after-sales step passed at 2026-10-21T13:00:30 UTC",
                deadline.getMessage());
        Mockito.verifyNoInteractions(views);
    }


    @Test
    void escalate_shouldKeepATrimmedNoteAndDropABlankOne() {
        runRequestsInline();
        AfterSalesCaseEntity rejected = caseRow(AfterSalesStatus.REJECTED);
        Mockito.when(caseRepository.lockByIdAndCustomerId(CASE_ID, CUSTOMER_ID)).thenReturn(rejected);
        Mockito.when(transitions.fire(Mockito.eq(rejected), Mockito.any())).thenReturn(new AfterSalesTransitions.Step(
                new Decision.AlreadyDone<>(AfterSalesTrigger.CUSTOMER_ESCALATE, AfterSalesStatus.REJECTED), rejected));
        stubDetail(rejected);

        service.escalate(CUSTOMER_ID, CASE_ID, "  Please look again.  ", "key-1");
        service.escalate(CUSTOMER_ID, CASE_ID, "   ", "key-2");
        service.escalate(CUSTOMER_ID, CASE_ID, null, "key-3");

        ArgumentCaptor<AfterSalesTransitions.Command> commands = ArgumentCaptor.forClass(
                AfterSalesTransitions.Command.class);
        Mockito.verify(transitions, Mockito.times(3)).fire(Mockito.eq(rejected), commands.capture());
        List<String> notes = new ArrayList<>();
        commands.getAllValues().forEach(command -> notes.add(command.note()));
        Assertions.assertEquals(java.util.Arrays.asList("Please look again.", null, null), notes);
        ArgumentCaptor<String> hashes = ArgumentCaptor.forClass(String.class);
        Mockito.verify(idempotentRequests, Mockito.times(3)).run(Mockito.any(), Mockito.any(), Mockito.any(),
                hashes.capture(), Mockito.any());
        Assertions.assertNotEquals(hashes.getAllValues().get(0), hashes.getAllValues().get(1), "the note is hashed");
        Assertions.assertEquals(hashes.getAllValues().get(1), hashes.getAllValues().get(2));
    }


    @Test
    void command_whoseCaseIsGoneAfterwards_shouldBeAnError() {
        Mockito.when(idempotentRequests.run(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(CASE_ID);
        Mockito.when(caseRepository.findById(CASE_ID)).thenReturn(Optional.empty());

        IllegalStateException gone = Assertions.assertThrows(IllegalStateException.class,
                () -> service.withdraw(CUSTOMER_ID, CASE_ID, "key-1"));

        Assertions.assertEquals("After-sales case 501 is gone", gone.getMessage());
    }


    // ---- queries ----


    @Test
    void getCase_shouldShowOnlyTheCustomersOwnCase() {
        AfterSalesCaseEntity own = caseRow(AfterSalesStatus.PENDING_MERCHANT);
        AfterSalesCaseDto answer = stubDetail(own);

        Assertions.assertSame(answer, service.getCase(CUSTOMER_ID, CASE_ID));
        Assertions.assertEquals("After-sales case not found", Assertions.assertThrows(ResourceNotFoundException.class,
                () -> service.getCase(CUSTOMER_ID + 1, CASE_ID)).getMessage());
        Mockito.when(caseRepository.findById(CASE_ID + 1)).thenReturn(Optional.empty());
        Assertions.assertThrows(ResourceNotFoundException.class, () -> service.getCase(CUSTOMER_ID, CASE_ID + 1));
    }


    @Test
    void getOverview_shouldListTheCasesAndTheOrdersThatCanGetARequestWithTheirAttempt() {
        Mockito.when(clock.get()).thenReturn(NOW);
        List<AfterSalesCaseEntity> page = List.of(caseRow(AfterSalesStatus.PENDING_MERCHANT));
        Mockito.when(caseRepository.findPageByCustomerId(CUSTOMER_ID, 50)).thenReturn(page);
        List<AfterSalesCaseDto> summaries = List.of();
        Mockito.when(views.summaries(page)).thenReturn(summaries);
        OrderEntity fresh = new OrderEntity(1002L, 9002L, CUSTOMER_ID, new BigDecimal("8.00"), "COMPLETED",
                NOW.minusHours(5), 7L, null, NOW.minusHours(1), "USD");
        OrderEntity withdrawnOnce = completedAt(NOW.minusHours(3));
        Mockito.when(orderRepository.findPageOpenToAfterSales(CUSTOMER_ID, NOW.minusHours(24), 50))
                .thenReturn(List.of(fresh, withdrawnOnce));
        AfterSalesCaseEntity automatic = new AfterSalesCaseEntity(600L, 1002L, CUSTOMER_ID, 7L, "ORDER_CANCELLED", 1,
                "REFUNDED", null, null, new BigDecimal("8.00"), new BigDecimal("8.00"), null, new BigDecimal("8.00"),
                new BigDecimal("8.00"), "USD", null, null, "ORDER_CANCELLED", null, null, null, null, 0, 2L, NOW, NOW);
        Mockito.when(caseRepository.findAllByOrderIdIn(List.of(1002L, ORDER_ID)))
                .thenReturn(List.of(automatic, closedRequest(1, AfterSalesCloseReason.WITHDRAWN)));

        AfterSalesOverviewDto overview = service.getOverview(CUSTOMER_ID, 50);

        Assertions.assertSame(summaries, overview.cases());
        Assertions.assertEquals(List.of(
                new EligibleOrderDto(1002L, 9002L, 1, NOW.plusHours(23)),
                new EligibleOrderDto(ORDER_ID, ORDER_NO, 2, NOW.plusHours(21))
        ), overview.eligibleOrders());
    }


    @ParameterizedTest
    @CsvSource({"0,1", "-5,1", "100,100", "500,100"})
    void getOverview_shouldKeepThePageBetweenOneAndAHundred(int limit, int pageSize) {
        Mockito.when(clock.get()).thenReturn(NOW);

        AfterSalesOverviewDto overview = service.getOverview(CUSTOMER_ID, limit);

        Mockito.verify(caseRepository).findPageByCustomerId(CUSTOMER_ID, pageSize);
        Mockito.verify(orderRepository).findPageOpenToAfterSales(CUSTOMER_ID, NOW.minusHours(24), pageSize);
        Mockito.verify(caseRepository, Mockito.never()).findAllByOrderIdIn(Mockito.any());
        Assertions.assertEquals(List.of(), overview.eligibleOrders());
    }


    // ---- fixtures ----


    /**
     * An order of 16.09 USD completed two hours before {@link #NOW}: a Whopper Meal and two Large Fries. The same
     * instance every time, so that a test can tell the locked order is the one passed on.
     */
    static OrderEntity order() {
        return ORDER;
    }


    static OrderEntity completedAt(LocalDateTime completedAt) {
        return new OrderEntity(ORDER_ID, ORDER_NO, CUSTOMER_ID, new BigDecimal("16.09"), "COMPLETED",
                completedAt.minusHours(1), 7L, null, completedAt, "USD");
    }


    static OrderEntity withStatus(OrderEntity order, String status) {
        return order.withStatus(status, null, order.updatedAt());
    }


    static List<OrderHistoryItemEntity> items() {
        return List.of(
                new OrderHistoryItemEntity(41L, ORDER_ID, 5L, 7L, new BigDecimal("10.59"), 1, "Whopper Meal", null,
                        null, "USD"),
                new OrderHistoryItemEntity(42L, ORDER_ID, 6L, 7L, new BigDecimal("2.75"), 2, "Large Fries", null, null,
                        "USD")
        );
    }


    static OrderPaymentEntity payment(String amount) {
        return new OrderPaymentEntity(77L, ORDER_ID, CUSTOMER_ID, new BigDecimal(amount), "USD", "CAPTURED",
                "SIMPAY-0f3a", NOW.minusHours(3));
    }


    /**
     * A customer's first request of the order in {@code status}, deadline an hour after {@link #NOW}.
     */
    static AfterSalesCaseEntity caseRow(AfterSalesStatus status) {
        return new AfterSalesCaseEntity(CASE_ID, ORDER_ID, CUSTOMER_ID, 7L, "CUSTOMER_REQUEST", 1, status.name(),
                "MISSING_ITEM", "The fries were missing.", new BigDecimal("16.09"), new BigDecimal("16.09"), null, null,
                null, "USD", null, null, null, null, null, status.isOpen() ? NOW.plusHours(1) : null, null, 0, 1L,
                NOW.minusHours(1), NOW.minusHours(1));
    }


    static AfterSalesCaseEntity closedRequest(int attemptNo, AfterSalesCloseReason reason) {
        return new AfterSalesCaseEntity(CASE_ID + attemptNo, ORDER_ID, CUSTOMER_ID, 7L, "CUSTOMER_REQUEST", attemptNo,
                "CLOSED", "MISSING_ITEM", "The fries were missing.", new BigDecimal("16.09"), new BigDecimal("16.09"),
                null, null, null, "USD", null, null, null, reason.name(), null, null, null, 0, 1L, NOW.minusHours(1),
                NOW.minusHours(1));
    }


    /**
     * The first request, CLOSED as WITHDRAWN, with what the merchant had answered before (a decision and an offer, or
     * nothing); which status it was withdrawn from is in its history only.
     */
    static AfterSalesCaseEntity withdrawnRequest(String merchantDecision, String offeredAmount) {
        return new AfterSalesCaseEntity(CASE_ID + 1, ORDER_ID, CUSTOMER_ID, 7L, "CUSTOMER_REQUEST", 1, "CLOSED",
                "MISSING_ITEM", "The fries were missing.", new BigDecimal("16.09"), new BigDecimal("16.09"),
                offeredAmount == null ? null : new BigDecimal(offeredAmount), null, null, "USD", merchantDecision,
                merchantDecision == null ? null : "Answered.", null, "WITHDRAWN", null, null, null, 0, 3L,
                NOW.minusHours(1), NOW.minusMinutes(5));
    }


    static OpenAfterSalesBody wholeOrder() {
        return new OpenAfterSalesBody("WHOLE_ORDER", null, "MISSING_ITEM", "The fries were missing.");
    }


    static OpenAfterSalesBody items(OpenAfterSalesLine... lines) {
        return new OpenAfterSalesBody("ITEMS", List.of(lines), "QUALITY_ISSUE", "It was cold.");
    }


    private AfterSalesCaseDto command(String route, String key) {
        return switch (route) {
            case "accept-offer" -> service.acceptOffer(CUSTOMER_ID, CASE_ID, key);
            case "decline-offer" -> service.declineOffer(CUSTOMER_ID, CASE_ID, key);
            case "escalate" -> service.escalate(CUSTOMER_ID, CASE_ID, null, key);
            default -> service.withdraw(CUSTOMER_ID, CASE_ID, key);
        };
    }


    /**
     * The idempotent requests run their request in place, as on a first run.
     */
    private void runRequestsInline() {
        Mockito.when(idempotentRequests.run(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenAnswer(invocation -> invocation.<Supplier<IdempotentRequests.Binding>>getArgument(4).get()
                        .caseId());
    }


    private void stubEligibleOrder(OrderEntity order, OrderPaymentEntity payment, List<AfterSalesCaseEntity> cases) {
        runRequestsInline();
        Mockito.when(orderRepository.lockByIdAndCustomerId(ORDER_ID, CUSTOMER_ID)).thenReturn(order);
        Mockito.when(clock.get()).thenReturn(NOW);
        Mockito.when(orderPaymentRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.of(payment));
        Mockito.when(caseRepository.findAllByOrderId(ORDER_ID)).thenReturn(cases);
    }


    /**
     * An eligible order whose request the writer opens as case {@link #CASE_ID}; returns the answer.
     */
    private AfterSalesCaseDto eligibleOrderOpens(OrderEntity order, List<AfterSalesCaseEntity> cases) {
        stubEligibleOrder(order, payment("16.09"), cases);
        Mockito.when(orderHistoryItemRepository.findAllByOrderIdOrderByIdAsc(ORDER_ID)).thenReturn(items());
        AfterSalesCaseEntity opened = caseRow(AfterSalesStatus.PENDING_MERCHANT);
        Mockito.when(transitions.open(Mockito.any())).thenReturn(opened);
        return stubDetail(opened);
    }


    private AfterSalesCaseDto stubDetail(AfterSalesCaseEntity current) {
        AfterSalesCaseDto answer = new AfterSalesCaseDto(current, ORDER_NO, List.of(), null, List.of());
        Mockito.when(caseRepository.findById(current.id())).thenReturn(Optional.of(current));
        Mockito.when(views.detail(current)).thenReturn(answer);
        return answer;
    }


    private AfterSalesTransitions.CustomerRequest openedRequest() {
        ArgumentCaptor<AfterSalesTransitions.CustomerRequest> request = ArgumentCaptor.forClass(
                AfterSalesTransitions.CustomerRequest.class);
        Mockito.verify(transitions).open(request.capture());
        return request.getValue();
    }
}
