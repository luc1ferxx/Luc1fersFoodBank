package com.laioffer.onlineorder.service;


import com.laioffer.onlineorder.audit.AuditActions;
import com.laioffer.onlineorder.audit.AuditActor;
import com.laioffer.onlineorder.audit.AuditLogService;
import com.laioffer.onlineorder.entity.IdempotencyRequestEntity;
import com.laioffer.onlineorder.entity.OrderEntity;
import com.laioffer.onlineorder.entity.OrderHistoryItemEntity;
import com.laioffer.onlineorder.exception.BadRequestException;
import com.laioffer.onlineorder.exception.ConflictException;
import com.laioffer.onlineorder.exception.ResourceNotFoundException;
import com.laioffer.onlineorder.fulfilment.AfterCommit;
import com.laioffer.onlineorder.fulfilment.order.OrderActor;
import com.laioffer.onlineorder.fulfilment.order.OrderLifecycleCommands;
import com.laioffer.onlineorder.model.OrderDto;
import com.laioffer.onlineorder.model.OrderHistoryItemDto;
import com.laioffer.onlineorder.model.OrderStateMachine;
import com.laioffer.onlineorder.model.OrderStatus;
import com.laioffer.onlineorder.model.OrderStatusHistoryDto;
import com.laioffer.onlineorder.model.OrderTransitionContext;
import com.laioffer.onlineorder.model.OrderTrigger;
import com.laioffer.onlineorder.observability.ApplicationMetricsService;
import com.laioffer.onlineorder.repository.IdempotencyRequestRepository;
import com.laioffer.onlineorder.repository.OrderHistoryItemRepository;
import com.laioffer.onlineorder.repository.OrderRepository;
import com.laioffer.onlineorder.statemachine.CompareAndSet;
import com.laioffer.onlineorder.statemachine.Decision;
import com.laioffer.onlineorder.statemachine.StaleTransitionException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;


/**
 * The order module: reads orders and owns the order state machine. Every status change goes through
 * {@link #applyTransition} with the trigger its caller names: {@link OrderStateMachine} decides it on the status read
 * under the order's row lock, and an applied move writes the new status and updated_at with a compare-and-set on that
 * status, the order_status_history row and the order event to the outbox, all in the caller's transaction. A write
 * that does not change exactly one row throws {@link StaleTransitionException} and rolls back; with the lock held it
 * cannot happen. Each caller maps a trigger that already happened or that the status does not allow to its own answer.
 *
 * <p>A move from PAID or ACCEPTED to CANCELLED also opens the order's automatic refund ({@link CancellationRefunds}),
 * after the order's event and in the same transaction, so the cancel and its refund commit or roll back together. The
 * merchant's answers to after-sales requests go to the after-sales module ({@link AfterSalesMerchantCommands}), the
 * other half of the seam's after-sales command.
 */
@Service
public class OrderService implements OrderLifecycleCommands {

    /**
     * The history reason of the steps a delivered order is completed with.
     */
    static final String DELIVERED_REASON = "DELIVERED";

    /**
     * The row named in a {@link StaleTransitionException}.
     */
    private static final String ORDER_ROW = "order";

    /**
     * The statuses of a paid order that can be cancelled (ADR 0002 D12): a cancel from one of them is refunded. A
     * PLACED order was never paid.
     */
    private static final Set<OrderStatus> REFUNDED_WHEN_CANCELLED = Set.of(OrderStatus.PAID, OrderStatus.ACCEPTED);

    private static final int IDEMPOTENCY_KEY_MAX_LENGTH = 255;
    private static final String IDEMPOTENCY_STATUS_PROCESSING = "PROCESSING";
    private static final String IDEMPOTENCY_STATUS_SUCCEEDED = "SUCCEEDED";
    private static final String ORDER_STATUS_OPERATION = "ORDER_STATUS_TRANSITION";
    private static final String ORDER_CANCEL_OPERATION = "ORDER_CANCEL";
    private static final String ORDER_STATUS_METHOD = "PATCH";
    private static final String ORDER_CANCEL_METHOD = "POST";
    private static final String ORDER_STATUS_ROUTE = "/orders/{orderId}/status";
    private static final String ORDER_CANCEL_ROUTE = "/orders/{orderId}/cancel";
    private static final String ORDER_STATUS_SCOPE_PREFIX = "order-status:v1:";
    private static final String ORDER_CANCEL_SCOPE_PREFIX = "order-cancel:v1:";

    private final ApplicationMetricsService metricsService;
    private final IdempotencyRequestRepository idempotencyRequestRepository;
    private final IdempotencyRequestInitializer idempotencyRequestInitializer;
    private final OrderEventOutboxService orderEventOutboxService;
    private final OrderRepository orderRepository;
    private final OrderHistoryItemRepository orderHistoryItemRepository;
    private final OrderStatusHistoryService orderStatusHistoryService;
    private final AuditLogService auditLogService;
    private final CancellationRefunds cancellationRefunds;
    private final AfterSalesMerchantCommands afterSalesMerchantCommands;


    public OrderService(
            OrderRepository orderRepository,
            OrderHistoryItemRepository orderHistoryItemRepository,
            OrderEventOutboxService orderEventOutboxService,
            ApplicationMetricsService metricsService,
            IdempotencyRequestRepository idempotencyRequestRepository,
            IdempotencyRequestInitializer idempotencyRequestInitializer,
            OrderStatusHistoryService orderStatusHistoryService,
            AuditLogService auditLogService,
            CancellationRefunds cancellationRefunds,
            AfterSalesMerchantCommands afterSalesMerchantCommands
    ) {
        this.orderRepository = orderRepository;
        this.orderHistoryItemRepository = orderHistoryItemRepository;
        this.orderEventOutboxService = orderEventOutboxService;
        this.metricsService = metricsService;
        this.idempotencyRequestRepository = idempotencyRequestRepository;
        this.idempotencyRequestInitializer = idempotencyRequestInitializer;
        this.orderStatusHistoryService = orderStatusHistoryService;
        this.auditLogService = auditLogService;
        this.cancellationRefunds = cancellationRefunds;
        this.afterSalesMerchantCommands = afterSalesMerchantCommands;
    }


    @Transactional(readOnly = true)
    public List<OrderDto> getOrdersByCustomerId(Long customerId) {
        return getOrdersByCustomerId(customerId, 50);
    }


    /**
     * The customer's newest orders with their items (GET /orders). Read-only: a replica read when replicas are
     * configured, except within the customer's read-your-writes window after their own checkout or cancel.
     */
    @Transactional(readOnly = true)
    public List<OrderDto> getOrdersByCustomerId(Long customerId, int limit) {
        List<OrderEntity> orders = orderRepository.findPageByCustomerIdOrderByCreatedAtDesc(customerId, normalizeLimit(limit));
        Map<Long, List<OrderHistoryItemDto>> itemsByOrderId = getOrderItemsByOrderId(orders);
        List<OrderDto> results = new ArrayList<>();
        for (OrderEntity order : orders) {
            results.add(new OrderDto(order, itemsByOrderId.getOrDefault(order.id(), List.of())));
        }
        return results;
    }


    public OrderDto getOrderDto(OrderEntity order) {
        return new OrderDto(order, getOrderItems(order.id()));
    }


    /**
     * The status changes of an order, oldest first, for its owner or an admin. Anyone else gets 404, the same as for
     * an order that does not exist, so order ids cannot be probed. Read-only (a replica read when replicas are configured).
     */
    @Transactional(readOnly = true)
    public List<OrderStatusHistoryDto> getStatusHistory(Long orderId, Long readerCustomerId, boolean readerIsAdmin) {
        OrderEntity order = (readerIsAdmin
                ? orderRepository.findById(orderId)
                : orderRepository.findByIdAndCustomerId(orderId, readerCustomerId))
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        return orderStatusHistoryService.getHistory(orderId, order.createdAt());
    }


    /**
     * A status change without a known actor (recorded as SYSTEM). The admin endpoint uses
     * {@link #idempotentTransitionOrderStatus}.
     */
    @Transactional
    public OrderDto transitionOrderStatus(Long orderId, String targetStatus) {
        return transitionOrderStatus(orderId, targetStatus, OrderActor.system());
    }


    @Transactional
    public OrderDto transitionOrderStatus(Long orderId, String targetStatus, OrderActor actor) {
        OrderEntity order = orderRepository.lockById(orderId);
        if (order == null) {
            throw new ResourceNotFoundException("Order not found");
        }
        OrderStatus normalizedTargetStatus = OrderStatus.normalize(targetStatus);
        return transitionLockedOrder(
                order,
                OrderStateMachine.adminTrigger(normalizedTargetStatus),
                normalizedTargetStatus,
                OrderLifecycleCommands.ADMIN_CANCELLED,
                actor
        ).order();
    }


    /**
     * The admin status change (PATCH /orders/{orderId}/status, ROLE_ADMIN only): recorded in the order history with
     * an ADMIN actor and in the audit log, once per idempotency key.
     */
    @Transactional
    public OrderDto idempotentTransitionOrderStatus(Long actorCustomerId, Long orderId, String targetStatus, String idempotencyKey) {
        OrderStatus normalizedTargetStatus = OrderStatus.normalize(targetStatus);
        String normalizedKey = normalizeIdempotencyKey(idempotencyKey);
        String scope = ORDER_STATUS_SCOPE_PREFIX + orderId;
        String requestHash = buildRequestHash(
                ORDER_STATUS_OPERATION,
                ORDER_STATUS_METHOD,
                ORDER_STATUS_ROUTE,
                orderId,
                actorCustomerId,
                normalizedTargetStatus
        );
        return runIdempotentOrderOperation(
                actorCustomerId,
                orderId,
                scope,
                normalizedKey,
                requestHash,
                () -> {
                    OrderEntity order = orderRepository.lockById(orderId);
                    if (order == null) {
                        throw new ResourceNotFoundException("Order not found");
                    }
                    TransitionResult result = transitionLockedOrder(
                            order,
                            OrderStateMachine.adminTrigger(normalizedTargetStatus),
                            normalizedTargetStatus,
                            OrderLifecycleCommands.ADMIN_CANCELLED,
                            OrderActor.admin(actorCustomerId)
                    );
                    Map<String, Object> details = new LinkedHashMap<>();
                    details.put("from_status", result.fromStatus().name());
                    details.put("to_status", normalizedTargetStatus.name());
                    details.put("changed", result.changed());
                    details.put("idempotency_key", normalizedKey);
                    auditLogService.record(
                            AuditActor.admin(actorCustomerId),
                            AuditActions.ORDER_STATUS_UPDATE,
                            AuditActions.TARGET_ORDER,
                            orderId,
                            details
                    );
                    return result.order();
                }
        );
    }


    @Transactional
    public OrderDto cancelOrder(Long customerId, Long orderId) {
        OrderEntity order = orderRepository.lockByIdAndCustomerId(orderId, customerId);
        if (order == null) {
            throw new ResourceNotFoundException("Order not found");
        }
        return transitionLockedOrder(
                order,
                OrderTrigger.CUSTOMER_CANCEL,
                OrderStatus.CANCELLED,
                OrderLifecycleCommands.CUSTOMER_CANCELLED,
                OrderActor.customer(customerId)
        ).order();
    }


    @Transactional
    public OrderDto idempotentCancelOrder(Long actorCustomerId, Long orderId, String idempotencyKey) {
        String normalizedKey = normalizeIdempotencyKey(idempotencyKey);
        String scope = ORDER_CANCEL_SCOPE_PREFIX + orderId;
        String requestHash = buildRequestHash(
                ORDER_CANCEL_OPERATION,
                ORDER_CANCEL_METHOD,
                ORDER_CANCEL_ROUTE,
                orderId,
                actorCustomerId,
                OrderStatus.CANCELLED
        );
        return runIdempotentOrderOperation(
                actorCustomerId,
                orderId,
                scope,
                normalizedKey,
                requestHash,
                () -> {
                    OrderEntity order = orderRepository.lockByIdAndCustomerId(orderId, actorCustomerId);
                    if (order == null) {
                        throw new ResourceNotFoundException("Order not found");
                    }
                    return transitionLockedOrder(
                            order,
                            OrderTrigger.CUSTOMER_CANCEL,
                            OrderStatus.CANCELLED,
                            OrderLifecycleCommands.CUSTOMER_CANCELLED,
                            OrderActor.customer(actorCustomerId)
                    ).order();
                }
        );
    }


    private OrderDto runIdempotentOrderOperation(
            Long actorCustomerId,
            Long orderId,
            String scope,
            String idempotencyKey,
            String requestHash,
            Supplier<OrderDto> operation
    ) {
        LocalDateTime now = LocalDateTime.now();
        int inserted = idempotencyRequestInitializer.insertIfAbsent(
                actorCustomerId,
                scope,
                idempotencyKey,
                requestHash,
                IDEMPOTENCY_STATUS_PROCESSING,
                now,
                now
        );

        IdempotencyRequestEntity request = idempotencyRequestRepository.lockByCustomerIdAndScopeAndIdempotencyKey(
                actorCustomerId,
                scope,
                idempotencyKey
        );
        if (request == null) {
            throw new ConflictException("Unable to initialize idempotent order operation");
        }
        if (!request.requestHash().equals(requestHash)) {
            throw new ConflictException("Idempotency key was already used with a different request");
        }
        if (IDEMPOTENCY_STATUS_SUCCEEDED.equals(request.status())) {
            if (request.orderId() == null) {
                throw new ConflictException("Idempotent order operation completed without a stored order");
            }
            return getRequiredOrderDto(request.orderId());
        }
        if (!IDEMPOTENCY_STATUS_PROCESSING.equals(request.status()) || inserted == 0) {
            throw new ConflictException("Idempotent order operation is still processing");
        }

        OrderDto order = operation.get();
        idempotencyRequestRepository.markSucceeded(request.id(), IDEMPOTENCY_STATUS_SUCCEEDED, order.id(), LocalDateTime.now());
        return order;
    }


    @Override
    @Transactional
    public RestaurantOrderTransition transitionRestaurantOrder(
            Long orderId,
            Long restaurantId,
            OrderStatus requiredStatus,
            OrderStatus targetStatus,
            String cancelReason,
            OrderActor actor
    ) {
        OrderTrigger trigger = OrderStateMachine.restaurantTrigger(requiredStatus, targetStatus);
        OrderEntity order = orderRepository.lockById(orderId);
        if (order == null || restaurantId == null || !restaurantId.equals(order.restaurantId())) {
            throw new ResourceNotFoundException("Order not found");
        }
        TransitionOutcome outcome = applyTransition(
                order,
                trigger,
                new OrderTransitionContext(order.cancelReason(), cancelReason),
                actor,
                cancelReason,
                targetStatus == OrderStatus.CANCELLED ? cancelReason : null
        );
        if (outcome.decision() instanceof Decision.Rejected<OrderStatus, OrderTrigger> rejected) {
            String reason = rejected.current() == OrderStatus.CANCELLED && order.cancelReason() != null
                    ? " (" + OrderTransitionContext.cancelReasonCode(order.cancelReason()) + ")"
                    : "";
            throw new ConflictException(
                    OrderLifecycleCommands.ORDER_STATUS_CONFLICT,
                    "Order is " + rejected.current() + reason + "; only " + requiredStatus + " orders can move to "
                            + targetStatus
            );
        }
        // Already done: a repeated command gets the order unchanged.
        return new RestaurantOrderTransition(orderDto(outcome), outcome.applied());
    }


    @Override
    @Transactional
    public Optional<OrderDto> cancelIfStillIn(
            Long orderId,
            OrderStatus expectedStatus,
            String cancelReason,
            OrderActor actor
    ) {
        OrderTrigger trigger = OrderStateMachine.timeoutTrigger(expectedStatus);
        OrderEntity order = orderRepository.lockById(orderId);
        if (order == null) {
            return Optional.empty();
        }
        // A timeout fires only from the status it waits in; the machine rejects it from any other and nothing changes.
        TransitionOutcome outcome = applyTransition(order, trigger, null, actor, cancelReason, cancelReason);
        return outcome.applied() ? Optional.of(orderDto(outcome)) : Optional.empty();
    }


    @Override
    @Transactional
    public DeliveryCompletion completeDeliveredOrder(Long orderId, OrderActor actor) {
        OrderEntity order = orderRepository.lockById(orderId);
        if (order == null) {
            return DeliveryCompletion.NOT_FOUND;
        }
        // Each step is fired only from the status it moves from, so the machine applies it.
        return switch (OrderStatus.normalize(order.status())) {
            case PREPARING -> {
                applyTransition(order, OrderTrigger.DELIVERY_COMPLETE, null, actor, null, DELIVERED_REASON);
                yield DeliveryCompletion.COMPLETED;
            }
            case ACCEPTED -> {
                OrderEntity preparing = applyTransition(
                        order, OrderTrigger.DELIVERY_PREPARE, null, actor, null, DELIVERED_REASON).order();
                applyTransition(preparing, OrderTrigger.DELIVERY_COMPLETE, null, actor, null, DELIVERED_REASON);
                yield DeliveryCompletion.COMPLETED;
            }
            case COMPLETED -> DeliveryCompletion.ALREADY_COMPLETED;
            case PLACED, PAID, CANCELLED -> DeliveryCompletion.IGNORED;
        };
    }


    /**
     * The after-sales module locks the case, decides with the after-sales machine and writes the step, all in this
     * transaction.
     */
    @Override
    @Transactional
    public AfterSalesDecision decideAfterSalesCase(
            Long caseId,
            Long restaurantId,
            AfterSalesResponse response,
            OrderActor actor
    ) {
        return afterSalesMerchantCommands.decide(caseId, restaurantId, response, actor);
    }


    /**
     * Fires a customer or admin trigger on a locked order. A trigger that already happened returns the order unchanged
     * (changed false); one the order's status does not allow is a 409 CONFLICT naming the move.
     *
     * @param targetStatus the status the caller asked for, named in the 409
     * @param cancelReason stored on the order (and as the history reason) when {@code targetStatus} is CANCELLED;
     *                     ignored otherwise
     */
    private TransitionResult transitionLockedOrder(
            OrderEntity currentOrder,
            OrderTrigger trigger,
            OrderStatus targetStatus,
            String cancelReason,
            OrderActor actor
    ) {
        OrderStatus currentStatus = OrderStatus.normalize(currentOrder.status());
        TransitionOutcome outcome = applyTransition(
                currentOrder,
                trigger,
                null,
                actor,
                cancelReason,
                targetStatus == OrderStatus.CANCELLED ? cancelReason : null
        );
        if (outcome.decision() instanceof Decision.Rejected) {
            throw new ConflictException("Invalid order status transition: " + currentStatus + " -> " + targetStatus);
        }
        // Already done: a retry, or an admin PATCH to the status the order is in, gets the order unchanged.
        return new TransitionResult(orderDto(outcome), currentStatus, outcome.applied());
    }


    /**
     * Decides {@code trigger} on a locked order with {@link OrderStateMachine}. When the machine applies it, writes the
     * new status (with its cancel reason and updated_at) with a compare-and-set on the status the order was read in,
     * then its order_status_history row and its order event to the outbox, in the same transaction and all with the
     * same time. A cancel of a paid order (PAID or ACCEPTED) then opens its refund, still in that transaction and at
     * that time. A trigger that already happened or that the status does not allow writes nothing.
     *
     * @param context       what the trigger's checks read; null for the triggers that read none
     * @param cancelReason  stored on the order when the trigger cancels it; the order keeps its reason otherwise
     * @param historyReason the reason recorded in the history row
     * @throws StaleTransitionException when the compare-and-set does not change exactly one row
     */
    private TransitionOutcome applyTransition(
            OrderEntity currentOrder,
            OrderTrigger trigger,
            OrderTransitionContext context,
            OrderActor actor,
            String cancelReason,
            String historyReason
    ) {
        OrderStatus currentStatus = OrderStatus.normalize(currentOrder.status());
        Decision<OrderStatus, OrderTrigger> decision =
                OrderStateMachine.DEFINITION.decide(currentStatus, trigger, actor.type(), context);
        if (!(decision instanceof Decision.Apply<OrderStatus, OrderTrigger> move)) {
            return new TransitionOutcome(decision, currentOrder, null);
        }
        OrderStatus targetStatus = move.to();
        LocalDateTime transitionedAt = LocalDateTime.now();
        OrderEntity updatedOrder = currentOrder.withStatus(
                targetStatus.name(),
                targetStatus == OrderStatus.CANCELLED ? cancelReason : currentOrder.cancelReason(),
                transitionedAt
        );
        int rows = orderRepository.compareAndSetStatus(
                updatedOrder.id(),
                currentStatus.name(),
                updatedOrder.status(),
                updatedOrder.cancelReason(),
                transitionedAt
        );
        CompareAndSet.expectOneRow(rows, ORDER_ROW, updatedOrder.id(), currentStatus);
        orderStatusHistoryService.record(
                updatedOrder.id(),
                currentStatus,
                targetStatus,
                actor,
                historyReason,
                transitionedAt
        );
        List<OrderHistoryItemDto> items = getOrderItems(updatedOrder.id());
        orderEventOutboxService.enqueueOrderEvent(updatedOrder, items, null, transitionedAt);
        if (targetStatus == OrderStatus.CANCELLED && REFUNDED_WHEN_CANCELLED.contains(currentStatus)) {
            // After the order's own event (ADR 0002 D12): the order row stays locked, and the refund commits with the
            // cancel or rolls it back.
            cancellationRefunds.refundCancelledOrder(updatedOrder, transitionedAt);
        }
        // Counted once the transition commits: a later step of the caller (its audit entry, its idempotency record)
        // can still roll it back.
        AfterCommit.run(() -> metricsService.recordOrderTransition(currentStatus.name(), targetStatus.name()));
        return new TransitionOutcome(decision, updatedOrder, items);
    }


    /**
     * The order after a trigger: as written (with the items its event carries) when the trigger was applied, the
     * locked order with its items otherwise.
     */
    private OrderDto orderDto(TransitionOutcome outcome) {
        return outcome.applied() ? new OrderDto(outcome.order(), outcome.items()) : getOrderDto(outcome.order());
    }


    /**
     * @param decision what the machine decided
     * @param order    the order as written for an applied trigger; the locked order, unchanged, otherwise
     * @param items    the order's items, loaded for its event; null when nothing was applied
     */
    private record TransitionOutcome(
            Decision<OrderStatus, OrderTrigger> decision,
            OrderEntity order,
            List<OrderHistoryItemDto> items
    ) {

        boolean applied() {
            return decision instanceof Decision.Apply;
        }
    }


    /**
     * @param fromStatus the status the order was in before the command
     * @param changed    false for a same-status no-op
     */
    private record TransitionResult(OrderDto order, OrderStatus fromStatus, boolean changed) {
    }


    private OrderDto getRequiredOrderDto(Long orderId) {
        OrderEntity order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        return getOrderDto(order);
    }


    private List<OrderHistoryItemDto> getOrderItems(Long orderId) {
        List<OrderHistoryItemEntity> orderItems = orderHistoryItemRepository.findAllByOrderIdOrderByIdAsc(orderId);
        List<OrderHistoryItemDto> itemDtos = new ArrayList<>();
        for (OrderHistoryItemEntity orderItem : orderItems) {
            itemDtos.add(new OrderHistoryItemDto(orderItem));
        }
        return itemDtos;
    }


    private Map<Long, List<OrderHistoryItemDto>> getOrderItemsByOrderId(List<OrderEntity> orders) {
        List<Long> orderIds = orders.stream()
                .map(OrderEntity::id)
                .toList();
        Map<Long, List<OrderHistoryItemDto>> itemsByOrderId = new HashMap<>();
        if (orderIds.isEmpty()) {
            return itemsByOrderId;
        }
        for (OrderHistoryItemEntity item : orderHistoryItemRepository.findAllByOrderIdInOrderByOrderIdAscIdAsc(orderIds)) {
            itemsByOrderId.computeIfAbsent(item.orderId(), ignored -> new ArrayList<>())
                    .add(new OrderHistoryItemDto(item));
        }
        return itemsByOrderId;
    }


    private int normalizeLimit(int limit) {
        return Math.max(1, Math.min(limit, 100));
    }


    private String normalizeIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BadRequestException("Idempotency-Key header is required");
        }
        String normalizedKey = idempotencyKey.trim();
        if (normalizedKey.length() > IDEMPOTENCY_KEY_MAX_LENGTH) {
            throw new BadRequestException("Idempotency-Key header must be 255 characters or fewer");
        }
        return normalizedKey;
    }


    private String buildRequestHash(
            String operation,
            String method,
            String routeTemplate,
            Long orderId,
            Long actorCustomerId,
            OrderStatus targetStatus
    ) {
        String canonical = "operation=" + operation + "\n"
                + "method=" + method + "\n"
                + "route=" + routeTemplate + "\n"
                + "orderId=" + orderId + "\n"
                + "actorCustomerId=" + actorCustomerId + "\n"
                + "targetStatus=" + targetStatus.name();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }
}
