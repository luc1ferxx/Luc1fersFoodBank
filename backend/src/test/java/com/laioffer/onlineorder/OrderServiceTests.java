package com.laioffer.onlineorder;


import com.laioffer.onlineorder.audit.AuditActions;
import com.laioffer.onlineorder.audit.AuditActor;
import com.laioffer.onlineorder.entity.IdempotencyRequestEntity;
import com.laioffer.onlineorder.entity.OrderEntity;
import com.laioffer.onlineorder.entity.OrderHistoryItemEntity;
import com.laioffer.onlineorder.exception.BadRequestException;
import com.laioffer.onlineorder.exception.ConflictException;
import com.laioffer.onlineorder.exception.ResourceNotFoundException;
import com.laioffer.onlineorder.fulfilment.order.OrderActor;
import com.laioffer.onlineorder.fulfilment.order.OrderLifecycleCommands;
import com.laioffer.onlineorder.model.OrderDto;
import com.laioffer.onlineorder.model.OrderStatus;
import com.laioffer.onlineorder.model.OrderStatusHistoryDto;
import com.laioffer.onlineorder.money.Money;
import com.laioffer.onlineorder.observability.ApplicationMetricsService;
import com.laioffer.onlineorder.repository.IdempotencyRequestRepository;
import com.laioffer.onlineorder.repository.OrderHistoryItemRepository;
import com.laioffer.onlineorder.repository.OrderRepository;
import com.laioffer.onlineorder.service.CancellationRefunds;
import com.laioffer.onlineorder.service.IdempotencyRequestInitializer;
import com.laioffer.onlineorder.service.OrderEventOutboxService;
import com.laioffer.onlineorder.service.OrderService;
import com.laioffer.onlineorder.service.OrderStatusHistoryService;
import com.laioffer.onlineorder.audit.AuditLogService;
import com.laioffer.onlineorder.statemachine.StaleTransitionException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;


import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;


@ExtendWith(MockitoExtension.class)
class OrderServiceTests {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderHistoryItemRepository orderHistoryItemRepository;

    @Mock
    private OrderEventOutboxService orderEventOutboxService;

    @Mock
    private IdempotencyRequestRepository idempotencyRequestRepository;

    @Mock
    private IdempotencyRequestInitializer idempotencyRequestInitializer;

    @Mock
    private ApplicationMetricsService metricsService;

    @Mock
    private OrderStatusHistoryService orderStatusHistoryService;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private CancellationRefunds cancellationRefunds;

    private OrderService orderService;


    @BeforeEach
    void setup() {
        orderService = new OrderService(
                orderRepository,
                orderHistoryItemRepository,
                orderEventOutboxService,
                metricsService,
                idempotencyRequestRepository,
                idempotencyRequestInitializer,
                orderStatusHistoryService,
                auditLogService,
                cancellationRefunds
        );
    }


    @Test
    void transitionOrderStatus_shouldAdvanceThroughAllowedState() {
        OrderEntity paidOrder = new OrderEntity(5L, 1L, Money.of("19.0", "USD"), "PAID", LocalDateTime.of(2026, 4, 4, 12, 0));
        OrderHistoryItemEntity historyItem = new OrderHistoryItemEntity(
                6L,
                5L,
                3L,
                2L,
                amount("19.0"),
                1,
                "Burger",
                "Classic burger",
                "image",
                "USD"
        );

        Mockito.when(orderRepository.lockById(5L)).thenReturn(paidOrder);
        stubCompareAndSet();
        Mockito.when(orderHistoryItemRepository.findAllByOrderIdOrderByIdAsc(5L)).thenReturn(List.of(historyItem));

        LocalDateTime beforeTransition = LocalDateTime.now();
        OrderDto result = orderService.transitionOrderStatus(5L, "ACCEPTED");
        LocalDateTime afterTransition = LocalDateTime.now();

        Assertions.assertEquals("ACCEPTED", result.status());
        ArgumentCaptor<OrderEntity> written = ArgumentCaptor.forClass(OrderEntity.class);
        ArgumentCaptor<LocalDateTime> occurredAt = ArgumentCaptor.forClass(LocalDateTime.class);
        Mockito.verify(orderEventOutboxService).enqueueOrderEvent(
                written.capture(),
                Mockito.anyList(),
                Mockito.isNull(),
                occurredAt.capture()
        );
        // The event carries the order as the compare-and-set wrote it.
        Mockito.verify(orderRepository).compareAndSetStatus(5L, "PAID", "ACCEPTED", null, occurredAt.getValue());
        Assertions.assertEquals(paidOrder.withStatus("ACCEPTED", null, occurredAt.getValue()), written.getValue());
        Assertions.assertNotEquals(paidOrder.createdAt(), occurredAt.getValue());
        Assertions.assertFalse(occurredAt.getValue().isBefore(beforeTransition));
        Assertions.assertFalse(occurredAt.getValue().isAfter(afterTransition));
        Mockito.verify(metricsService).recordOrderTransition("PAID", "ACCEPTED");
    }


    @Test
    void cancelOrder_shouldRejectInvalidTransition() {
        OrderEntity completedOrder = new OrderEntity(5L, 1L, Money.of("19.0", "USD"), "COMPLETED", LocalDateTime.of(2026, 4, 4, 12, 0));

        Mockito.when(orderRepository.lockByIdAndCustomerId(5L, 1L)).thenReturn(completedOrder);

        Assertions.assertThrows(ConflictException.class, () -> orderService.cancelOrder(1L, 5L));
        Mockito.verify(orderRepository, Mockito.never())
                .compareAndSetStatus(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
    }


    @Test
    void transitionOrderStatus_withoutAnActor_shouldRecordSystemInTheHistory() {
        OrderEntity paidOrder = new OrderEntity(5L, 1L, Money.of("19.0", "USD"), "PAID", LocalDateTime.of(2026, 4, 4, 12, 0));
        Mockito.when(orderRepository.lockById(5L)).thenReturn(paidOrder);
        stubCompareAndSet();

        orderService.transitionOrderStatus(5L, "ACCEPTED");

        Mockito.verify(orderStatusHistoryService).record(
                Mockito.eq(5L),
                Mockito.eq(OrderStatus.PAID),
                Mockito.eq(OrderStatus.ACCEPTED),
                Mockito.eq(OrderActor.system()),
                Mockito.isNull(),
                Mockito.any(LocalDateTime.class)
        );
        Mockito.verifyNoInteractions(auditLogService);
    }


    @Test
    void idempotentTransitionOrderStatus_shouldRecordTheAdminInTheHistoryAndTheAuditLog() {
        OrderEntity paidOrder = new OrderEntity(5L, 1L, Money.of("19.0", "USD"), "PAID", LocalDateTime.of(2026, 4, 4, 12, 0));
        stubFirstIdempotentAttempt(9L, "admin-key");
        Mockito.when(orderRepository.lockById(5L)).thenReturn(paidOrder);
        stubCompareAndSet();

        OrderDto result = orderService.idempotentTransitionOrderStatus(9L, 5L, "ACCEPTED", "admin-key");

        Assertions.assertEquals("ACCEPTED", result.status());
        Mockito.verify(orderStatusHistoryService).record(
                Mockito.eq(5L),
                Mockito.eq(OrderStatus.PAID),
                Mockito.eq(OrderStatus.ACCEPTED),
                Mockito.eq(OrderActor.admin(9L)),
                Mockito.isNull(),
                Mockito.any(LocalDateTime.class)
        );
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, ?>> details = ArgumentCaptor.forClass(Map.class);
        Mockito.verify(auditLogService).record(
                Mockito.eq(AuditActor.admin(9L)),
                Mockito.eq(AuditActions.ORDER_STATUS_UPDATE),
                Mockito.eq(AuditActions.TARGET_ORDER),
                Mockito.eq(5L),
                details.capture()
        );
        Assertions.assertEquals("PAID", details.getValue().get("from_status"));
        Assertions.assertEquals("ACCEPTED", details.getValue().get("to_status"));
        Assertions.assertEquals(true, details.getValue().get("changed"));
        Assertions.assertEquals("admin-key", details.getValue().get("idempotency_key"));
    }


    @Test
    void idempotentTransitionOrderStatus_toTheCurrentStatus_shouldAuditTheNoOpWithoutHistory() {
        OrderEntity acceptedOrder = new OrderEntity(5L, 1L, Money.of("19.0", "USD"), "ACCEPTED", LocalDateTime.of(2026, 4, 4, 12, 0));
        stubFirstIdempotentAttempt(9L, "same-status-key");
        Mockito.when(orderRepository.lockById(5L)).thenReturn(acceptedOrder);

        orderService.idempotentTransitionOrderStatus(9L, 5L, "ACCEPTED", "same-status-key");

        Mockito.verifyNoInteractions(orderStatusHistoryService);
        Mockito.verify(orderRepository, Mockito.never())
                .compareAndSetStatus(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
        Mockito.verify(auditLogService).record(
                Mockito.eq(AuditActor.admin(9L)),
                Mockito.eq(AuditActions.ORDER_STATUS_UPDATE),
                Mockito.eq(AuditActions.TARGET_ORDER),
                Mockito.eq(5L),
                Mockito.argThat(details -> Boolean.FALSE.equals(details.get("changed")))
        );
    }


    @Test
    void adminPatch_illegalEdge_shouldConflictWithTheExactMessage() {
        OrderEntity completedOrder = order("COMPLETED", null);
        stubFirstIdempotentAttempt(9L, "illegal-key");
        Mockito.when(orderRepository.lockById(5L)).thenReturn(completedOrder);

        ConflictException conflict = Assertions.assertThrows(
                ConflictException.class,
                () -> orderService.idempotentTransitionOrderStatus(9L, 5L, "CANCELLED", "illegal-key")
        );

        Assertions.assertEquals(ConflictException.DEFAULT_CODE, conflict.getCode());
        Assertions.assertEquals("Invalid order status transition: COMPLETED -> CANCELLED", conflict.getMessage());
        // The PROCESSING row was inserted in the same transaction, which this exception rolls back.
        Mockito.verify(orderRepository).lockById(5L);
        assertNoOrderWrites();
        Mockito.verifyNoInteractions(auditLogService);
        Mockito.verify(idempotencyRequestRepository, Mockito.never())
                .markSucceeded(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
    }


    @Test
    void adminPatch_unknownStatus_shouldBeBadRequestBeforeTheIdempotencyRow() {
        BadRequestException badRequest = Assertions.assertThrows(
                BadRequestException.class,
                () -> orderService.idempotentTransitionOrderStatus(9L, 5L, "SHIPPED", "unknown-status-key")
        );

        Assertions.assertEquals(BadRequestException.DEFAULT_CODE, badRequest.getCode());
        Assertions.assertEquals("Unsupported order status: SHIPPED", badRequest.getMessage());
        Mockito.verifyNoInteractions(
                idempotencyRequestInitializer,
                idempotencyRequestRepository,
                orderRepository,
                auditLogService
        );
        assertNoOrderWrites();
    }


    @Test
    void adminPatch_placedOnAPlacedOrder_shouldBeAnAuditedNoOp() {
        OrderEntity placedOrder = order("PLACED", null);
        stubFirstIdempotentAttempt(9L, "placed-key");
        Mockito.when(orderRepository.lockById(5L)).thenReturn(placedOrder);

        OrderDto result = orderService.idempotentTransitionOrderStatus(9L, 5L, "PLACED", "placed-key");

        Assertions.assertEquals("PLACED", result.status());
        Mockito.verify(orderRepository).lockById(5L);
        assertNoOrderWrites();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, ?>> details = ArgumentCaptor.forClass(Map.class);
        Mockito.verify(auditLogService).record(
                Mockito.eq(AuditActor.admin(9L)),
                Mockito.eq(AuditActions.ORDER_STATUS_UPDATE),
                Mockito.eq(AuditActions.TARGET_ORDER),
                Mockito.eq(5L),
                details.capture()
        );
        Assertions.assertEquals("PLACED", details.getValue().get("from_status"));
        Assertions.assertEquals("PLACED", details.getValue().get("to_status"));
        Assertions.assertEquals(false, details.getValue().get("changed"));
        Assertions.assertEquals("placed-key", details.getValue().get("idempotency_key"));
        Mockito.verify(idempotencyRequestRepository)
                .markSucceeded(Mockito.eq(77L), Mockito.eq("SUCCEEDED"), Mockito.eq(5L), Mockito.any(LocalDateTime.class));
    }


    @Test
    void adminPatch_placedOnAPaidOrder_shouldConflict() {
        OrderEntity paidOrder = order("PAID", null);
        stubFirstIdempotentAttempt(9L, "back-to-placed-key");
        Mockito.when(orderRepository.lockById(5L)).thenReturn(paidOrder);

        ConflictException conflict = Assertions.assertThrows(
                ConflictException.class,
                () -> orderService.idempotentTransitionOrderStatus(9L, 5L, "PLACED", "back-to-placed-key")
        );

        Assertions.assertEquals(ConflictException.DEFAULT_CODE, conflict.getCode());
        Assertions.assertEquals("Invalid order status transition: PAID -> PLACED", conflict.getMessage());
        Mockito.verify(orderRepository).lockById(5L);
        assertNoOrderWrites();
        Mockito.verifyNoInteractions(auditLogService);
        Mockito.verify(idempotencyRequestRepository, Mockito.never())
                .markSucceeded(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
    }


    @Test
    void adminPatch_cancelled_shouldRecordAdminCancelled() {
        OrderEntity paidOrder = order("PAID", null);
        stubFirstIdempotentAttempt(9L, "admin-cancel-key");
        Mockito.when(orderRepository.lockById(5L)).thenReturn(paidOrder);
        stubCompareAndSet();

        OrderDto result = orderService.idempotentTransitionOrderStatus(9L, 5L, "CANCELLED", "admin-cancel-key");

        Assertions.assertEquals("CANCELLED", result.status());
        Assertions.assertEquals(OrderLifecycleCommands.ADMIN_CANCELLED, result.cancelReason());
        // The order row, the history row and the order event all carry ADMIN_CANCELLED.
        Mockito.verify(orderRepository).compareAndSetStatus(
                Mockito.eq(5L),
                Mockito.eq("PAID"),
                Mockito.eq("CANCELLED"),
                Mockito.eq(OrderLifecycleCommands.ADMIN_CANCELLED),
                Mockito.any(LocalDateTime.class)
        );
        Mockito.verify(orderStatusHistoryService).record(
                Mockito.eq(5L),
                Mockito.eq(OrderStatus.PAID),
                Mockito.eq(OrderStatus.CANCELLED),
                Mockito.eq(OrderActor.admin(9L)),
                Mockito.eq(OrderLifecycleCommands.ADMIN_CANCELLED),
                Mockito.any(LocalDateTime.class)
        );
        Mockito.verify(orderEventOutboxService).enqueueOrderEvent(
                Mockito.argThat(order -> "CANCELLED".equals(order.status())
                        && OrderLifecycleCommands.ADMIN_CANCELLED.equals(order.cancelReason())),
                Mockito.anyList(),
                Mockito.isNull(),
                Mockito.any(LocalDateTime.class)
        );
        Mockito.verify(metricsService).recordOrderTransition("PAID", "CANCELLED");
        Mockito.verify(auditLogService).record(
                Mockito.eq(AuditActor.admin(9L)),
                Mockito.eq(AuditActions.ORDER_STATUS_UPDATE),
                Mockito.eq(AuditActions.TARGET_ORDER),
                Mockito.eq(5L),
                Mockito.argThat(details -> "PAID".equals(details.get("from_status"))
                        && "CANCELLED".equals(details.get("to_status"))
                        && Boolean.TRUE.equals(details.get("changed")))
        );
    }


    @Test
    void customerCancel_ofAnOrderTheTimeoutCancelled_withANewKey_shouldKeepTheReason() {
        OrderEntity timedOutOrder = order("CANCELLED", OrderLifecycleCommands.MERCHANT_TIMEOUT);
        stubFirstIdempotentAttempt(1L, "new-cancel-key");
        Mockito.when(orderRepository.lockByIdAndCustomerId(5L, 1L)).thenReturn(timedOutOrder);

        OrderDto result = orderService.idempotentCancelOrder(1L, 5L, "new-cancel-key");

        Assertions.assertEquals("CANCELLED", result.status());
        Assertions.assertEquals(OrderLifecycleCommands.MERCHANT_TIMEOUT, result.cancelReason());
        Mockito.verify(orderRepository).lockByIdAndCustomerId(5L, 1L);
        assertNoOrderWrites();
        Mockito.verifyNoInteractions(auditLogService);
        // The new key's idempotency row is the only write: inserted PROCESSING, then marked SUCCEEDED for the order.
        Mockito.verify(idempotencyRequestInitializer).insertIfAbsent(
                Mockito.eq(1L), Mockito.eq("order-cancel:v1:5"), Mockito.eq("new-cancel-key"), Mockito.anyString(),
                Mockito.eq("PROCESSING"), Mockito.any(), Mockito.any()
        );
        Mockito.verify(idempotencyRequestRepository)
                .markSucceeded(Mockito.eq(77L), Mockito.eq("SUCCEEDED"), Mockito.eq(5L), Mockito.any(LocalDateTime.class));
    }


    @Test
    void customerCancel_ofAPreparingOrder_shouldConflictWithTheExactMessage() {
        OrderEntity preparingOrder = order("PREPARING", null);
        stubFirstIdempotentAttempt(1L, "late-cancel-key");
        Mockito.when(orderRepository.lockByIdAndCustomerId(5L, 1L)).thenReturn(preparingOrder);

        ConflictException idempotentConflict = Assertions.assertThrows(
                ConflictException.class,
                () -> orderService.idempotentCancelOrder(1L, 5L, "late-cancel-key")
        );
        ConflictException conflict = Assertions.assertThrows(
                ConflictException.class,
                () -> orderService.cancelOrder(1L, 5L)
        );

        for (ConflictException refused : List.of(idempotentConflict, conflict)) {
            Assertions.assertEquals(ConflictException.DEFAULT_CODE, refused.getCode());
            Assertions.assertEquals("Invalid order status transition: PREPARING -> CANCELLED", refused.getMessage());
        }
        Mockito.verify(orderRepository, Mockito.times(2)).lockByIdAndCustomerId(5L, 1L);
        assertNoOrderWrites();
        Mockito.verifyNoInteractions(auditLogService);
        Mockito.verify(idempotencyRequestRepository, Mockito.never())
                .markSucceeded(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
    }


    /**
     * ADR 0002 I14 (section 4.1): the idempotency row first, then the order row lock, then the status write, its
     * history row and its event (which takes the aggregate's sequence row), then the audit row and the idempotency
     * row's completion.
     */
    @Test
    void adminPatch_shouldLockAndWriteInTheDocumentedOrder() {
        stubFirstIdempotentAttempt(9L, "ordered-key");
        Mockito.when(orderRepository.lockById(5L)).thenReturn(order("PAID", null));
        stubCompareAndSet();

        orderService.idempotentTransitionOrderStatus(9L, 5L, "ACCEPTED", "ordered-key");

        InOrder steps = Mockito.inOrder(
                idempotencyRequestInitializer,
                idempotencyRequestRepository,
                orderRepository,
                orderStatusHistoryService,
                orderEventOutboxService,
                auditLogService
        );
        steps.verify(idempotencyRequestInitializer).insertIfAbsent(
                Mockito.eq(9L), Mockito.eq("order-status:v1:5"), Mockito.eq("ordered-key"), Mockito.anyString(),
                Mockito.eq("PROCESSING"), Mockito.any(), Mockito.any());
        steps.verify(idempotencyRequestRepository)
                .lockByCustomerIdAndScopeAndIdempotencyKey(9L, "order-status:v1:5", "ordered-key");
        steps.verify(orderRepository).lockById(5L);
        steps.verify(orderRepository).compareAndSetStatus(
                Mockito.eq(5L), Mockito.eq("PAID"), Mockito.eq("ACCEPTED"), Mockito.isNull(),
                Mockito.any(LocalDateTime.class));
        steps.verify(orderStatusHistoryService).record(
                Mockito.eq(5L), Mockito.eq(OrderStatus.PAID), Mockito.eq(OrderStatus.ACCEPTED),
                Mockito.eq(OrderActor.admin(9L)), Mockito.isNull(), Mockito.any(LocalDateTime.class));
        steps.verify(orderEventOutboxService).enqueueOrderEvent(
                Mockito.any(), Mockito.anyList(), Mockito.isNull(), Mockito.any(LocalDateTime.class));
        steps.verify(auditLogService).record(
                Mockito.eq(AuditActor.admin(9L)), Mockito.eq(AuditActions.ORDER_STATUS_UPDATE),
                Mockito.eq(AuditActions.TARGET_ORDER), Mockito.eq(5L), Mockito.any());
        steps.verify(idempotencyRequestRepository)
                .markSucceeded(Mockito.eq(77L), Mockito.eq("SUCCEEDED"), Mockito.eq(5L), Mockito.any(LocalDateTime.class));
        Mockito.verifyNoMoreInteractions(orderRepository, idempotencyRequestRepository, idempotencyRequestInitializer);
    }


    /**
     * ADR 0002 I14 for the customer's cancel: the same order as an admin PATCH, without the audit row.
     */
    @Test
    void customerCancel_shouldLockAndWriteInTheDocumentedOrder() {
        stubFirstIdempotentAttempt(1L, "ordered-cancel-key");
        Mockito.when(orderRepository.lockByIdAndCustomerId(5L, 1L)).thenReturn(order("PAID", null));
        stubCompareAndSet();

        orderService.idempotentCancelOrder(1L, 5L, "ordered-cancel-key");

        InOrder steps = Mockito.inOrder(
                idempotencyRequestInitializer,
                idempotencyRequestRepository,
                orderRepository,
                orderStatusHistoryService,
                orderEventOutboxService
        );
        steps.verify(idempotencyRequestInitializer).insertIfAbsent(
                Mockito.eq(1L), Mockito.eq("order-cancel:v1:5"), Mockito.eq("ordered-cancel-key"), Mockito.anyString(),
                Mockito.eq("PROCESSING"), Mockito.any(), Mockito.any());
        steps.verify(idempotencyRequestRepository)
                .lockByCustomerIdAndScopeAndIdempotencyKey(1L, "order-cancel:v1:5", "ordered-cancel-key");
        steps.verify(orderRepository).lockByIdAndCustomerId(5L, 1L);
        steps.verify(orderRepository).compareAndSetStatus(
                Mockito.eq(5L), Mockito.eq("PAID"), Mockito.eq("CANCELLED"),
                Mockito.eq(OrderLifecycleCommands.CUSTOMER_CANCELLED), Mockito.any(LocalDateTime.class));
        steps.verify(orderStatusHistoryService).record(
                Mockito.eq(5L), Mockito.eq(OrderStatus.PAID), Mockito.eq(OrderStatus.CANCELLED),
                Mockito.eq(OrderActor.customer(1L)), Mockito.eq(OrderLifecycleCommands.CUSTOMER_CANCELLED),
                Mockito.any(LocalDateTime.class));
        steps.verify(orderEventOutboxService).enqueueOrderEvent(
                Mockito.any(), Mockito.anyList(), Mockito.isNull(), Mockito.any(LocalDateTime.class));
        steps.verify(idempotencyRequestRepository)
                .markSucceeded(Mockito.eq(77L), Mockito.eq("SUCCEEDED"), Mockito.eq(5L), Mockito.any(LocalDateTime.class));
        Mockito.verifyNoMoreInteractions(orderRepository, idempotencyRequestRepository, idempotencyRequestInitializer);
        Mockito.verifyNoInteractions(auditLogService);
    }


    /**
     * Each target of the admin PATCH names its own ADMIN_* trigger, and together they make every move of the order
     * table, each written with a compare-and-set on the status the order was locked in (ADR 0002 section 4.1).
     */
    @ParameterizedTest
    @CsvSource(nullValues = "null", textBlock = """
            PLACED,    PAID,      null
            PLACED,    CANCELLED, ADMIN_CANCELLED
            PAID,      ACCEPTED,  null
            PAID,      CANCELLED, ADMIN_CANCELLED
            ACCEPTED,  PREPARING, null
            ACCEPTED,  CANCELLED, ADMIN_CANCELLED
            PREPARING, COMPLETED, null
            """)
    void adminPatch_everyMoveOfTheTable_shouldBeWrittenWithACompareAndSetOnTheLockedStatus(
            OrderStatus from,
            OrderStatus to,
            String reason
    ) {
        stubFirstIdempotentAttempt(9L, "move-key");
        Mockito.when(orderRepository.lockById(5L)).thenReturn(order(from.name(), null));
        stubCompareAndSet();

        OrderDto result = orderService.idempotentTransitionOrderStatus(9L, 5L, to.name(), "move-key");

        Assertions.assertEquals(to.name(), result.status());
        Assertions.assertEquals(reason, result.cancelReason());
        ArgumentCaptor<LocalDateTime> updatedAt = ArgumentCaptor.forClass(LocalDateTime.class);
        Mockito.verify(orderRepository).compareAndSetStatus(
                Mockito.eq(5L), Mockito.eq(from.name()), Mockito.eq(to.name()), Mockito.eq(reason), updatedAt.capture());
        Mockito.verify(orderStatusHistoryService).record(
                5L, from, to, OrderActor.admin(9L), reason, updatedAt.getValue());
        Mockito.verify(orderEventOutboxService).enqueueOrderEvent(
                Mockito.argThat(order -> to.name().equals(order.status())),
                Mockito.anyList(),
                Mockito.isNull(),
                Mockito.eq(updatedAt.getValue())
        );
        Mockito.verify(metricsService).recordOrderTransition(from.name(), to.name());
    }


    /**
     * A compare-and-set that changes no row means the row lock was not held: the command fails (and its transaction
     * rolls back) before the history row, the event, the metric and the audit entry.
     */
    @Test
    void adminPatch_whenTheCompareAndSetChangesNoRow_shouldThrowBeforeWritingAnythingElse() {
        stubFirstIdempotentAttempt(9L, "stale-key");
        Mockito.when(orderRepository.lockById(5L)).thenReturn(order("PAID", null));
        Mockito.when(orderRepository.compareAndSetStatus(
                Mockito.eq(5L), Mockito.eq("PAID"), Mockito.eq("ACCEPTED"), Mockito.isNull(), Mockito.any())
        ).thenReturn(0);

        StaleTransitionException stale = Assertions.assertThrows(
                StaleTransitionException.class,
                () -> orderService.idempotentTransitionOrderStatus(9L, 5L, "ACCEPTED", "stale-key")
        );

        Assertions.assertEquals("Compare-and-set of order 5 from PAID changed 0 rows instead of 1", stale.getMessage());
        Mockito.verifyNoInteractions(orderStatusHistoryService, orderEventOutboxService, metricsService, auditLogService);
        Mockito.verify(idempotencyRequestRepository, Mockito.never())
                .markSucceeded(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
    }


    /**
     * ADR 0002 D12: the customer's cancel of a paid order (PAID or ACCEPTED) opens its refund in the cancel's
     * transaction, after the order's event, with the order as written and the time of the cancel; the transition
     * metric and the idempotency record come after it.
     */
    @ParameterizedTest
    @CsvSource({"PAID", "ACCEPTED"})
    void customerCancel_ofAPaidOrder_shouldOpenItsRefundAfterTheOrderEvent(OrderStatus from) {
        stubFirstIdempotentAttempt(1L, "refund-cancel-key");
        OrderEntity locked = order(from.name(), null);
        Mockito.when(orderRepository.lockByIdAndCustomerId(5L, 1L)).thenReturn(locked);
        stubCompareAndSet();

        orderService.idempotentCancelOrder(1L, 5L, "refund-cancel-key");

        InOrder steps = Mockito.inOrder(
                orderRepository,
                orderStatusHistoryService,
                orderEventOutboxService,
                cancellationRefunds,
                metricsService,
                idempotencyRequestRepository
        );
        ArgumentCaptor<LocalDateTime> cancelledAt = ArgumentCaptor.forClass(LocalDateTime.class);
        steps.verify(orderRepository).compareAndSetStatus(
                Mockito.eq(5L), Mockito.eq(from.name()), Mockito.eq("CANCELLED"),
                Mockito.eq(OrderLifecycleCommands.CUSTOMER_CANCELLED), cancelledAt.capture());
        steps.verify(orderStatusHistoryService).record(
                5L, from, OrderStatus.CANCELLED, OrderActor.customer(1L), OrderLifecycleCommands.CUSTOMER_CANCELLED,
                cancelledAt.getValue());
        steps.verify(orderEventOutboxService).enqueueOrderEvent(
                Mockito.any(), Mockito.anyList(), Mockito.isNull(), Mockito.eq(cancelledAt.getValue()));
        steps.verify(cancellationRefunds).refundCancelledOrder(
                locked.withStatus("CANCELLED", OrderLifecycleCommands.CUSTOMER_CANCELLED, cancelledAt.getValue()),
                cancelledAt.getValue()
        );
        steps.verify(metricsService).recordOrderTransition(from.name(), "CANCELLED");
        steps.verify(idempotencyRequestRepository)
                .markSucceeded(Mockito.eq(77L), Mockito.eq("SUCCEEDED"), Mockito.eq(5L), Mockito.any(LocalDateTime.class));
        Mockito.verifyNoMoreInteractions(cancellationRefunds);
    }


    /**
     * An admin's cancel of a paid order opens the refund too, before the audit row of the PATCH.
     */
    @Test
    void adminCancel_ofAnAcceptedOrder_shouldOpenItsRefundBeforeTheAuditRow() {
        stubFirstIdempotentAttempt(9L, "refund-admin-key");
        Mockito.when(orderRepository.lockById(5L)).thenReturn(order("ACCEPTED", null));
        stubCompareAndSet();

        orderService.idempotentTransitionOrderStatus(9L, 5L, "CANCELLED", "refund-admin-key");

        InOrder steps = Mockito.inOrder(orderEventOutboxService, cancellationRefunds, auditLogService);
        steps.verify(orderEventOutboxService).enqueueOrderEvent(
                Mockito.any(), Mockito.anyList(), Mockito.isNull(), Mockito.any(LocalDateTime.class));
        steps.verify(cancellationRefunds).refundCancelledOrder(
                Mockito.argThat(order -> "CANCELLED".equals(order.status())
                        && OrderLifecycleCommands.ADMIN_CANCELLED.equals(order.cancelReason())),
                Mockito.any(LocalDateTime.class)
        );
        steps.verify(auditLogService).record(
                Mockito.eq(AuditActor.admin(9L)), Mockito.eq(AuditActions.ORDER_STATUS_UPDATE),
                Mockito.eq(AuditActions.TARGET_ORDER), Mockito.eq(5L), Mockito.any());
    }


    /**
     * Only a move from PAID or ACCEPTED to CANCELLED opens a refund: a PLACED order was never paid, and no other move
     * of the admin PATCH cancels an order.
     */
    @ParameterizedTest
    @CsvSource(textBlock = """
            PLACED,    PAID,      false
            PLACED,    CANCELLED, false
            PAID,      ACCEPTED,  false
            PAID,      CANCELLED, true
            ACCEPTED,  PREPARING, false
            ACCEPTED,  CANCELLED, true
            PREPARING, COMPLETED, false
            """)
    void adminPatch_shouldOpenARefundOnlyForTheCancelOfAPaidOrder(OrderStatus from, OrderStatus to, boolean refunded) {
        Mockito.when(orderRepository.lockById(5L)).thenReturn(order(from.name(), null));
        stubCompareAndSet();

        orderService.transitionOrderStatus(5L, to.name(), OrderActor.admin(9L));

        Mockito.verify(cancellationRefunds, Mockito.times(refunded ? 1 : 0))
                .refundCancelledOrder(Mockito.any(), Mockito.any());
    }


    /**
     * The customer's cancel of an unpaid order, and a repeated cancel of a cancelled one, open no refund.
     */
    @Test
    void customerCancel_ofAPlacedOrOfACancelledOrder_shouldOpenNoRefund() {
        Mockito.when(orderRepository.lockByIdAndCustomerId(5L, 1L)).thenReturn(
                order("PLACED", null),
                order("CANCELLED", OrderLifecycleCommands.MERCHANT_TIMEOUT)
        );
        stubCompareAndSet();

        Assertions.assertEquals("CANCELLED", orderService.cancelOrder(1L, 5L).status());
        Assertions.assertEquals(OrderLifecycleCommands.MERCHANT_TIMEOUT, orderService.cancelOrder(1L, 5L).cancelReason());

        Mockito.verify(orderRepository).compareAndSetStatus(
                Mockito.eq(5L), Mockito.eq("PLACED"), Mockito.eq("CANCELLED"),
                Mockito.eq(OrderLifecycleCommands.CUSTOMER_CANCELLED), Mockito.any(LocalDateTime.class));
        Mockito.verifyNoInteractions(cancellationRefunds);
    }


    /**
     * A refund that cannot be opened fails the cancel (its transaction rolls back): the transition is not counted and
     * the idempotency key is not completed, so a retry with the same key cancels again.
     */
    @Test
    void customerCancel_whenTheRefundCannotBeOpened_shouldFailWithoutCountingOrCompletingTheKey() {
        stubFirstIdempotentAttempt(1L, "failed-refund-key");
        Mockito.when(orderRepository.lockByIdAndCustomerId(5L, 1L)).thenReturn(order("PAID", null));
        stubCompareAndSet();
        Mockito.doThrow(new IllegalStateException("forced refund failure"))
                .when(cancellationRefunds).refundCancelledOrder(Mockito.any(), Mockito.any());

        IllegalStateException failed = Assertions.assertThrows(IllegalStateException.class,
                () -> orderService.idempotentCancelOrder(1L, 5L, "failed-refund-key"));

        Assertions.assertEquals("forced refund failure", failed.getMessage());
        Mockito.verify(orderEventOutboxService).enqueueOrderEvent(
                Mockito.any(), Mockito.anyList(), Mockito.isNull(), Mockito.any(LocalDateTime.class));
        Mockito.verifyNoInteractions(metricsService);
        Mockito.verify(idempotencyRequestRepository, Mockito.never())
                .markSucceeded(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
    }


    @Test
    void getStatusHistory_shouldOnlyServeTheOwnerAndAdmins() {
        List<OrderStatusHistoryDto> history = List.of(new OrderStatusHistoryDto(
                null, "PLACED", "CUSTOMER", 1L, null, LocalDateTime.of(2026, 4, 4, 12, 0)));
        OrderEntity order = new OrderEntity(5L, 1L, Money.of("19.0", "USD"), "PLACED", LocalDateTime.of(2026, 4, 4, 12, 0));
        Mockito.when(orderRepository.findByIdAndCustomerId(5L, 1L)).thenReturn(Optional.of(order));
        Mockito.when(orderRepository.findByIdAndCustomerId(5L, 2L)).thenReturn(Optional.empty());
        Mockito.when(orderRepository.findById(5L)).thenReturn(Optional.of(order));
        Mockito.when(orderRepository.findById(6L)).thenReturn(Optional.empty());
        // The creation time lets the history query skip the monthly partitions before the order.
        Mockito.when(orderStatusHistoryService.getHistory(5L, order.createdAt())).thenReturn(history);

        Assertions.assertEquals(history, orderService.getStatusHistory(5L, 1L, false));
        Assertions.assertEquals(history, orderService.getStatusHistory(5L, 3L, true));
        Assertions.assertThrows(ResourceNotFoundException.class, () -> orderService.getStatusHistory(5L, 2L, false));
        Assertions.assertThrows(ResourceNotFoundException.class, () -> orderService.getStatusHistory(6L, 3L, true));
        Mockito.verify(orderStatusHistoryService, Mockito.times(2)).getHistory(5L, order.createdAt());
    }


    /**
     * The first attempt with this key: the PROCESSING row is inserted and then read back with the hash it was
     * inserted with.
     */
    private void stubFirstIdempotentAttempt(Long actorCustomerId, String key) {
        AtomicReference<String> insertedHash = new AtomicReference<>();
        Mockito.when(idempotencyRequestInitializer.insertIfAbsent(
                Mockito.eq(actorCustomerId), Mockito.anyString(), Mockito.eq(key), Mockito.anyString(),
                Mockito.eq("PROCESSING"), Mockito.any(), Mockito.any()
        )).thenAnswer(invocation -> {
            insertedHash.set(invocation.getArgument(3));
            return 1;
        });
        Mockito.when(idempotencyRequestRepository.lockByCustomerIdAndScopeAndIdempotencyKey(
                Mockito.eq(actorCustomerId), Mockito.anyString(), Mockito.eq(key)
        )).thenAnswer(invocation -> new IdempotencyRequestEntity(
                77L, actorCustomerId, invocation.getArgument(1), key, insertedHash.get(), "PROCESSING", null,
                LocalDateTime.now(), LocalDateTime.now()));
    }


    /**
     * Every compare-and-set of an order's status changes its one row, as it does under the row lock.
     */
    private void stubCompareAndSet() {
        Mockito.when(orderRepository.compareAndSetStatus(
                Mockito.anyLong(), Mockito.anyString(), Mockito.anyString(), Mockito.any(),
                Mockito.any(LocalDateTime.class)
        )).thenReturn(1);
    }


    /**
     * Order 5 of customer 1 at restaurant 2.
     */
    private OrderEntity order(String status, String cancelReason) {
        return new OrderEntity(5L, 1L, Money.of("19.0", "USD"), status, LocalDateTime.of(2026, 4, 4, 12, 0), 2L, cancelReason);
    }


    /**
     * No status write, history row, order event or transition metric. Call it after verifying the order lock(s) the
     * command takes: any other call on the order repository fails it, whichever method carries the status write (a
     * never-save check would pass silently once the write is a compare-and-set).
     */
    private void assertNoOrderWrites() {
        Mockito.verifyNoMoreInteractions(orderRepository);
        Mockito.verifyNoInteractions(orderStatusHistoryService, orderEventOutboxService, metricsService);
    }


    private BigDecimal amount(String value) {
        return new BigDecimal(value);
    }
}
