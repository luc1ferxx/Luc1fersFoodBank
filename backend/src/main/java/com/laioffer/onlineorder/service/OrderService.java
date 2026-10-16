package com.laioffer.onlineorder.service;


import com.laioffer.onlineorder.entity.IdempotencyRequestEntity;
import com.laioffer.onlineorder.entity.OrderEntity;
import com.laioffer.onlineorder.entity.OrderHistoryItemEntity;
import com.laioffer.onlineorder.exception.BadRequestException;
import com.laioffer.onlineorder.exception.ConflictException;
import com.laioffer.onlineorder.exception.ResourceNotFoundException;
import com.laioffer.onlineorder.fulfilment.order.OrderLifecycleCommands;
import com.laioffer.onlineorder.model.OrderDto;
import com.laioffer.onlineorder.model.OrderHistoryItemDto;
import com.laioffer.onlineorder.model.OrderStatus;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;


@Service
public class OrderService implements OrderLifecycleCommands {

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


    public OrderService(
            OrderRepository orderRepository,
            OrderHistoryItemRepository orderHistoryItemRepository,
            OrderEventOutboxService orderEventOutboxService,
            ApplicationMetricsService metricsService,
            IdempotencyRequestRepository idempotencyRequestRepository,
            IdempotencyRequestInitializer idempotencyRequestInitializer
    ) {
        this.orderRepository = orderRepository;
        this.orderHistoryItemRepository = orderHistoryItemRepository;
        this.orderEventOutboxService = orderEventOutboxService;
        this.metricsService = metricsService;
        this.idempotencyRequestRepository = idempotencyRequestRepository;
        this.idempotencyRequestInitializer = idempotencyRequestInitializer;
    }


    public List<OrderDto> getOrdersByCustomerId(Long customerId) {
        return getOrdersByCustomerId(customerId, 50);
    }


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


    @Transactional
    public OrderDto transitionOrderStatus(Long orderId, String targetStatus) {
        OrderEntity order = orderRepository.lockById(orderId);
        if (order == null) {
            throw new ResourceNotFoundException("Order not found");
        }
        return transitionLockedOrder(order, OrderStatus.normalize(targetStatus), OrderLifecycleCommands.ADMIN_CANCELLED);
    }


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
                    return transitionLockedOrder(order, normalizedTargetStatus, OrderLifecycleCommands.ADMIN_CANCELLED);
                }
        );
    }


    @Transactional
    public OrderDto cancelOrder(Long customerId, Long orderId) {
        OrderEntity order = orderRepository.lockByIdAndCustomerId(orderId, customerId);
        if (order == null) {
            throw new ResourceNotFoundException("Order not found");
        }
        return transitionLockedOrder(order, OrderStatus.CANCELLED, OrderLifecycleCommands.CUSTOMER_CANCELLED);
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
                    return transitionLockedOrder(order, OrderStatus.CANCELLED, OrderLifecycleCommands.CUSTOMER_CANCELLED);
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
            String cancelReason
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
        return new RestaurantOrderTransition(transitionLockedOrder(order, targetStatus, cancelReason), true);
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
    public Optional<OrderDto> cancelIfStillIn(Long orderId, OrderStatus expectedStatus, String cancelReason) {
        OrderEntity order = orderRepository.lockById(orderId);
        if (order == null || OrderStatus.normalize(order.status()) != expectedStatus) {
            return Optional.empty();
        }
        return Optional.of(transitionLockedOrder(order, OrderStatus.CANCELLED, cancelReason));
    }


    @Override
    @Transactional
    public DeliveryCompletion completeDeliveredOrder(Long orderId) {
        OrderEntity order = orderRepository.lockById(orderId);
        if (order == null) {
            return DeliveryCompletion.NOT_FOUND;
        }
        return switch (OrderStatus.normalize(order.status())) {
            case PREPARING -> {
                applyTransition(order, OrderStatus.COMPLETED, null);
                yield DeliveryCompletion.COMPLETED;
            }
            case ACCEPTED -> {
                OrderEntity preparing = applyTransition(order, OrderStatus.PREPARING, null).order();
                applyTransition(preparing, OrderStatus.COMPLETED, null);
                yield DeliveryCompletion.COMPLETED;
            }
            case COMPLETED -> DeliveryCompletion.ALREADY_COMPLETED;
            case PLACED, PAID, CANCELLED -> DeliveryCompletion.IGNORED;
        };
    }


    /**
     * @param cancelReason stored on the order when {@code targetStatus} is CANCELLED; ignored otherwise
     */
    private OrderDto transitionLockedOrder(OrderEntity currentOrder, OrderStatus targetStatus, String cancelReason) {
        OrderStatus currentStatus = OrderStatus.normalize(currentOrder.status());
        currentStatus.validateTransitionTo(targetStatus);
        if (currentStatus == targetStatus) {
            return getOrderDto(currentOrder);
        }

        AppliedTransition transition = applyTransition(currentOrder, targetStatus, cancelReason);
        return new OrderDto(transition.order(), transition.items());
    }


    /**
     * Saves the new status of a locked order and writes its order event to the outbox in the same transaction.
     */
    private AppliedTransition applyTransition(OrderEntity currentOrder, OrderStatus targetStatus, String cancelReason) {
        OrderStatus currentStatus = OrderStatus.normalize(currentOrder.status());
        currentStatus.validateTransitionTo(targetStatus);
        LocalDateTime transitionedAt = LocalDateTime.now();
        OrderEntity updatedOrder = orderRepository.save(new OrderEntity(
                currentOrder.id(),
                currentOrder.customerId(),
                currentOrder.totalPrice(),
                targetStatus.name(),
                currentOrder.createdAt(),
                currentOrder.restaurantId(),
                targetStatus == OrderStatus.CANCELLED ? cancelReason : currentOrder.cancelReason()
        ));
        List<OrderHistoryItemDto> items = getOrderItems(updatedOrder.id());
        orderEventOutboxService.enqueueOrderEvent(updatedOrder, items, null, transitionedAt);
        metricsService.recordOrderTransition(currentStatus.name(), targetStatus.name());
        return new AppliedTransition(updatedOrder, items);
    }


    private record AppliedTransition(OrderEntity order, List<OrderHistoryItemDto> items) {
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
