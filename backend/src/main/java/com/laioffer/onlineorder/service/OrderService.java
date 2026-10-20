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
import com.laioffer.onlineorder.model.OrderStatus;
import com.laioffer.onlineorder.model.OrderStatusHistoryDto;
import com.laioffer.onlineorder.observability.ApplicationMetricsService;
import com.laioffer.onlineorder.repository.IdempotencyRequestRepository;
import com.laioffer.onlineorder.repository.OrderHistoryItemRepository;
import com.laioffer.onlineorder.repository.OrderRepository;
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
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;


/**
 * The order module: reads orders and owns the order state machine. Every status change goes through
 * {@link #applyTransition}, which saves the new status and updated_at, writes the order_status_history row and the
 * order event to the outbox, all in the caller's transaction and under the order's row lock.
 */
@Service
public class OrderService implements OrderLifecycleCommands {

    /**
     * The history reason of the steps a delivered order is completed with.
     */
    static final String DELIVERED_REASON = "DELIVERED";

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


    public OrderService(
            OrderRepository orderRepository,
            OrderHistoryItemRepository orderHistoryItemRepository,
            OrderEventOutboxService orderEventOutboxService,
            ApplicationMetricsService metricsService,
            IdempotencyRequestRepository idempotencyRequestRepository,
            IdempotencyRequestInitializer idempotencyRequestInitializer,
            OrderStatusHistoryService orderStatusHistoryService,
            AuditLogService auditLogService
    ) {
        this.orderRepository = orderRepository;
        this.orderHistoryItemRepository = orderHistoryItemRepository;
        this.orderEventOutboxService = orderEventOutboxService;
        this.metricsService = metricsService;
        this.idempotencyRequestRepository = idempotencyRequestRepository;
        this.idempotencyRequestInitializer = idempotencyRequestInitializer;
        this.orderStatusHistoryService = orderStatusHistoryService;
        this.auditLogService = auditLogService;
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
        return transitionLockedOrder(order, OrderStatus.normalize(targetStatus), OrderLifecycleCommands.ADMIN_CANCELLED, actor)
                .order();
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
        OrderEntity order = orderRepository.lockById(orderId);
        if (order == null || restaurantId == null || !restaurantId.equals(order.restaurantId())) {
            throw new ResourceNotFoundException("Order not found");
        }
        OrderStatus currentStatus = OrderStatus.normalize(order.status());
        if (currentStatus == targetStatus
                && (targetStatus != OrderStatus.CANCELLED
                || Objects.equals(cancelReasonCode(order.cancelReason()), cancelReasonCode(cancelReason)))) {
            return new RestaurantOrderTransition(getOrderDto(order), false);
        }
        if (currentStatus != requiredStatus) {
            String reason = currentStatus == OrderStatus.CANCELLED && order.cancelReason() != null
                    ? " (" + cancelReasonCode(order.cancelReason()) + ")"
                    : "";
            throw new ConflictException(
                    OrderLifecycleCommands.ORDER_STATUS_CONFLICT,
                    "Order is " + currentStatus + reason + "; only " + requiredStatus + " orders can move to "
                            + targetStatus
            );
        }
        return new RestaurantOrderTransition(transitionLockedOrder(order, targetStatus, cancelReason, actor).order(), true);
    }


    /**
     * The code of a cancel reason: MERCHANT_REJECTED for "MERCHANT_REJECTED: out of buns".
     */
    private static String cancelReasonCode(String cancelReason) {
        if (cancelReason == null) {
            return null;
        }
        int separator = cancelReason.indexOf(':');
        return (separator < 0 ? cancelReason : cancelReason.substring(0, separator)).trim();
    }


    @Override
    @Transactional
    public Optional<OrderDto> cancelIfStillIn(
            Long orderId,
            OrderStatus expectedStatus,
            String cancelReason,
            OrderActor actor
    ) {
        OrderEntity order = orderRepository.lockById(orderId);
        if (order == null || OrderStatus.normalize(order.status()) != expectedStatus) {
            return Optional.empty();
        }
        return Optional.of(transitionLockedOrder(order, OrderStatus.CANCELLED, cancelReason, actor).order());
    }


    @Override
    @Transactional
    public DeliveryCompletion completeDeliveredOrder(Long orderId, OrderActor actor) {
        OrderEntity order = orderRepository.lockById(orderId);
        if (order == null) {
            return DeliveryCompletion.NOT_FOUND;
        }
        return switch (OrderStatus.normalize(order.status())) {
            case PREPARING -> {
                applyTransition(order, OrderStatus.COMPLETED, null, actor, DELIVERED_REASON);
                yield DeliveryCompletion.COMPLETED;
            }
            case ACCEPTED -> {
                OrderEntity preparing = applyTransition(order, OrderStatus.PREPARING, null, actor, DELIVERED_REASON).order();
                applyTransition(preparing, OrderStatus.COMPLETED, null, actor, DELIVERED_REASON);
                yield DeliveryCompletion.COMPLETED;
            }
            case COMPLETED -> DeliveryCompletion.ALREADY_COMPLETED;
            case PLACED, PAID, CANCELLED -> DeliveryCompletion.IGNORED;
        };
    }


    /**
     * @param cancelReason stored on the order (and as the history reason) when {@code targetStatus} is CANCELLED;
     *                     ignored otherwise
     */
    private TransitionResult transitionLockedOrder(
            OrderEntity currentOrder,
            OrderStatus targetStatus,
            String cancelReason,
            OrderActor actor
    ) {
        OrderStatus currentStatus = OrderStatus.normalize(currentOrder.status());
        currentStatus.validateTransitionTo(targetStatus);
        if (currentStatus == targetStatus) {
            return new TransitionResult(getOrderDto(currentOrder), currentStatus, false);
        }

        String historyReason = targetStatus == OrderStatus.CANCELLED ? cancelReason : null;
        AppliedTransition transition = applyTransition(currentOrder, targetStatus, cancelReason, actor, historyReason);
        return new TransitionResult(new OrderDto(transition.order(), transition.items()), currentStatus, true);
    }


    /**
     * Saves the new status (and updated_at) of a locked order, and writes its order_status_history row and its order
     * event to the outbox, in the same transaction.
     */
    private AppliedTransition applyTransition(
            OrderEntity currentOrder,
            OrderStatus targetStatus,
            String cancelReason,
            OrderActor actor,
            String historyReason
    ) {
        OrderStatus currentStatus = OrderStatus.normalize(currentOrder.status());
        currentStatus.validateTransitionTo(targetStatus);
        LocalDateTime transitionedAt = LocalDateTime.now();
        OrderEntity updatedOrder = orderRepository.save(currentOrder.withStatus(
                targetStatus.name(),
                targetStatus == OrderStatus.CANCELLED ? cancelReason : currentOrder.cancelReason(),
                transitionedAt
        ));
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
        // Counted once the transition commits: a later step of the caller (its audit entry, its idempotency record)
        // can still roll it back.
        AfterCommit.run(() -> metricsService.recordOrderTransition(currentStatus.name(), targetStatus.name()));
        return new AppliedTransition(updatedOrder, items);
    }


    private record AppliedTransition(OrderEntity order, List<OrderHistoryItemDto> items) {
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
