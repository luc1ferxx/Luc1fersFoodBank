package com.laioffer.onlineorder.service;


import com.laioffer.onlineorder.entity.IdempotencyRequestEntity;
import com.laioffer.onlineorder.entity.OrderEntity;
import com.laioffer.onlineorder.entity.OrderHistoryItemEntity;
import com.laioffer.onlineorder.exception.BadRequestException;
import com.laioffer.onlineorder.exception.ConflictException;
import com.laioffer.onlineorder.exception.ResourceNotFoundException;
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
import java.util.List;
import java.util.function.Supplier;


@Service
public class OrderService {

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
    private final OrderEventOutboxService orderEventOutboxService;
    private final OrderRepository orderRepository;
    private final OrderHistoryItemRepository orderHistoryItemRepository;


    public OrderService(
            OrderRepository orderRepository,
            OrderHistoryItemRepository orderHistoryItemRepository,
            OrderEventOutboxService orderEventOutboxService,
            ApplicationMetricsService metricsService,
            IdempotencyRequestRepository idempotencyRequestRepository
    ) {
        this.orderRepository = orderRepository;
        this.orderHistoryItemRepository = orderHistoryItemRepository;
        this.orderEventOutboxService = orderEventOutboxService;
        this.metricsService = metricsService;
        this.idempotencyRequestRepository = idempotencyRequestRepository;
    }


    public List<OrderDto> getOrdersByCustomerId(Long customerId) {
        List<OrderEntity> orders = orderRepository.findAllByCustomerIdOrderByCreatedAtDesc(customerId);
        List<OrderDto> results = new ArrayList<>();
        for (OrderEntity order : orders) {
            results.add(getOrderDto(order));
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
        return transitionLockedOrder(order, OrderStatus.normalize(targetStatus));
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
                    return transitionLockedOrder(order, normalizedTargetStatus);
                }
        );
    }


    @Transactional
    public OrderDto cancelOrder(Long customerId, Long orderId) {
        OrderEntity order = orderRepository.lockByIdAndCustomerId(orderId, customerId);
        if (order == null) {
            throw new ResourceNotFoundException("Order not found");
        }
        return transitionLockedOrder(order, OrderStatus.CANCELLED);
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
                    return transitionLockedOrder(order, OrderStatus.CANCELLED);
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
        int inserted = idempotencyRequestRepository.insertIfAbsent(
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


    private OrderDto transitionLockedOrder(OrderEntity currentOrder, OrderStatus targetStatus) {
        OrderStatus currentStatus = OrderStatus.normalize(currentOrder.status());
        currentStatus.validateTransitionTo(targetStatus);
        if (currentStatus == targetStatus) {
            return getOrderDto(currentOrder);
        }

        OrderEntity updatedOrder = orderRepository.save(new OrderEntity(
                currentOrder.id(),
                currentOrder.customerId(),
                currentOrder.totalPrice(),
                targetStatus.name(),
                currentOrder.createdAt()
        ));
        List<OrderHistoryItemDto> items = getOrderItems(updatedOrder.id());
        orderEventOutboxService.enqueueOrderEvent(updatedOrder, items, null);
        metricsService.recordOrderTransition(currentStatus.name(), targetStatus.name());
        return new OrderDto(updatedOrder, items);
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
