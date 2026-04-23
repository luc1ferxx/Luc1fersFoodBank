package com.laioffer.onlineorder.service;


import com.laioffer.onlineorder.entity.CartEntity;
import com.laioffer.onlineorder.entity.IdempotencyRequestEntity;
import com.laioffer.onlineorder.entity.MenuItemEntity;
import com.laioffer.onlineorder.entity.OrderEntity;
import com.laioffer.onlineorder.entity.OrderHistoryItemEntity;
import com.laioffer.onlineorder.entity.OrderItemEntity;
import com.laioffer.onlineorder.exception.BadRequestException;
import com.laioffer.onlineorder.exception.ConflictException;
import com.laioffer.onlineorder.exception.ResourceNotFoundException;
import com.laioffer.onlineorder.model.CartDto;
import com.laioffer.onlineorder.model.OrderDto;
import com.laioffer.onlineorder.model.OrderHistoryItemDto;
import com.laioffer.onlineorder.model.OrderItemDto;
import com.laioffer.onlineorder.model.OrderStatus;
import com.laioffer.onlineorder.observability.ApplicationMetricsService;
import com.laioffer.onlineorder.repository.CartRepository;
import com.laioffer.onlineorder.repository.IdempotencyRequestRepository;
import com.laioffer.onlineorder.repository.MenuItemRepository;
import com.laioffer.onlineorder.repository.OrderHistoryItemRepository;
import com.laioffer.onlineorder.repository.OrderItemRepository;
import com.laioffer.onlineorder.repository.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;


@Service
public class CartService {

    private static final String IDEMPOTENCY_STATUS_PROCESSING = "PROCESSING";
    private static final String IDEMPOTENCY_STATUS_SUCCEEDED = "SUCCEEDED";

    private final CartRepository cartRepository;
    private final IdempotencyRequestRepository idempotencyRequestRepository;
    private final MenuItemRepository menuItemRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderRepository orderRepository;
    private final OrderHistoryItemRepository orderHistoryItemRepository;
    private final OrderEventOutboxService orderEventOutboxService;
    private final ApplicationMetricsService metricsService;


    public CartService(
            CartRepository cartRepository,
            IdempotencyRequestRepository idempotencyRequestRepository,
            MenuItemRepository menuItemRepository,
            OrderItemRepository orderItemRepository,
            OrderRepository orderRepository,
            OrderHistoryItemRepository orderHistoryItemRepository,
            OrderEventOutboxService orderEventOutboxService,
            ApplicationMetricsService metricsService
    ) {
        this.cartRepository = cartRepository;
        this.idempotencyRequestRepository = idempotencyRequestRepository;
        this.menuItemRepository = menuItemRepository;
        this.orderItemRepository = orderItemRepository;
        this.orderRepository = orderRepository;
        this.orderHistoryItemRepository = orderHistoryItemRepository;
        this.orderEventOutboxService = orderEventOutboxService;
        this.metricsService = metricsService;
    }


    @Transactional
    public void addMenuItemToCart(long customerId, long menuItemId) {
        CartEntity cart = getRequiredLockedCart(customerId);
        MenuItemEntity menuItem = getRequiredMenuItem(menuItemId);
        orderItemRepository.incrementQuantity(cart.id(), menuItem.id(), menuItem.price());
        syncCartTotal(cart.id());
    }


    public CartDto getCart(Long customerId) {
        CartEntity cart = getRequiredCart(customerId);
        return buildCartDto(cart);
    }


    @Transactional
    public void updateOrderItemQuantity(Long customerId, Long orderItemId, Integer quantity) {
        if (quantity == null || quantity < 0) {
            throw new BadRequestException("Quantity must be zero or greater");
        }

        CartEntity cart = getRequiredLockedCart(customerId);
        OrderItemEntity orderItem = findOwnedOrderItem(cart.id(), orderItemId);

        if (orderItem == null) {
            if (quantity == 0) {
                syncCartTotal(cart.id());
                return;
            }
            throw new ResourceNotFoundException("Cart item not found");
        }

        if (quantity == 0) {
            orderItemRepository.deleteByIdAndCartId(orderItem.id(), cart.id());
        } else {
            OrderItemEntity updatedOrderItem = new OrderItemEntity(
                    orderItem.id(),
                    orderItem.menuItemId(),
                    orderItem.cartId(),
                    orderItem.price(),
                    quantity
            );
            orderItemRepository.save(updatedOrderItem);
        }

        syncCartTotal(cart.id());
    }


    @Transactional
    public void clearCart(Long customerId) {
        CartEntity cart = getRequiredLockedCart(customerId);
        orderItemRepository.deleteByCartId(cart.id());
        cartRepository.updateTotalPrice(cart.id(), 0.0);
    }


    @Transactional
    public OrderDto checkout(Long customerId) {
        CartEntity cart = getRequiredLockedCart(customerId);
        return checkoutInternal(customerId, cart, "PLACED", null);
    }


    @Transactional
    public OrderDto checkout(Long customerId, String status) {
        CartEntity cart = getRequiredLockedCart(customerId);
        return checkoutInternal(customerId, cart, status, null);
    }


    @Transactional
    public OrderDto checkoutWithIdempotency(Long customerId, String status, String idempotencyKey) {
        return checkoutWithIdempotency(customerId, status, idempotencyKey, buildDefaultRequestHash(status));
    }


    @Transactional
    public OrderDto checkoutWithIdempotency(Long customerId, String status, String idempotencyKey, String requestHash) {
        String normalizedStatus = normalizeStatus(status);
        String normalizedKey = normalizeIdempotencyKey(idempotencyKey);
        CartEntity cart = getRequiredLockedCart(customerId);
        String effectiveRequestHash = buildCheckoutRequestHash(cart, requestHash, normalizedStatus);
        LocalDateTime now = LocalDateTime.now();
        String scope = buildScope(normalizedStatus);

        idempotencyRequestRepository.insertIfAbsent(
                customerId,
                scope,
                normalizedKey,
                effectiveRequestHash,
                IDEMPOTENCY_STATUS_PROCESSING,
                now,
                now
        );

        IdempotencyRequestEntity request = idempotencyRequestRepository.lockByCustomerIdAndScopeAndIdempotencyKey(
                customerId,
                scope,
                normalizedKey
        );
        if (request == null) {
            throw new ConflictException("Unable to initialize idempotent checkout");
        }
        if (!request.requestHash().equals(effectiveRequestHash)) {
            if (IDEMPOTENCY_STATUS_SUCCEEDED.equals(request.status()) && request.orderId() != null) {
                String persistedOrderRequestHash = buildCheckoutRequestHashForOrder(cart.id(), requestHash, normalizedStatus, request.orderId());
                if (request.requestHash().equals(persistedOrderRequestHash)) {
                    return getRequiredOrderDto(request.orderId());
                }
            }
            throw new ConflictException("Idempotency key was already used with a different request");
        }
        if (IDEMPOTENCY_STATUS_SUCCEEDED.equals(request.status())) {
            if (request.orderId() == null) {
                throw new ConflictException("Idempotent checkout completed without a stored order");
            }
            return getRequiredOrderDto(request.orderId());
        }

        OrderDto order = checkoutInternal(customerId, cart, normalizedStatus, normalizedKey);
        idempotencyRequestRepository.markSucceeded(request.id(), IDEMPOTENCY_STATUS_SUCCEEDED, order.id(), LocalDateTime.now());
        return order;
    }


    private OrderDto checkoutInternal(Long customerId, CartEntity cart, String status, String idempotencyKey) {
        List<OrderItemEntity> cartItems = orderItemRepository.getAllByCartId(cart.id());
        if (cartItems.isEmpty()) {
            throw new BadRequestException("Your cart is empty");
        }

        syncCartTotal(cart.id());
        CartEntity refreshedCart = getRequiredCart(customerId);

        OrderEntity savedOrder = orderRepository.save(new OrderEntity(
                null,
                customerId,
                refreshedCart.totalPrice(),
                normalizeStatus(status),
                LocalDateTime.now()
        ));

        List<OrderHistoryItemEntity> orderHistoryItems = new ArrayList<>();
        List<OrderHistoryItemDto> orderHistoryItemDtos = new ArrayList<>();
        for (OrderItemEntity cartItem : cartItems) {
            MenuItemEntity menuItem = getRequiredMenuItem(cartItem.menuItemId());
            OrderHistoryItemEntity orderHistoryItem = new OrderHistoryItemEntity(
                    null,
                    savedOrder.id(),
                    cartItem.menuItemId(),
                    menuItem.restaurantId(),
                    cartItem.price(),
                    cartItem.quantity(),
                    menuItem.name(),
                    menuItem.description(),
                    menuItem.imageUrl()
            );
            orderHistoryItems.add(orderHistoryItem);
        }

        for (OrderHistoryItemEntity savedItem : orderHistoryItemRepository.saveAll(orderHistoryItems)) {
            orderHistoryItemDtos.add(new OrderHistoryItemDto(savedItem));
        }

        orderEventOutboxService.enqueueOrderEvent(savedOrder, orderHistoryItemDtos, idempotencyKey);
        metricsService.recordCheckout(savedOrder.status());
        orderItemRepository.deleteByCartId(cart.id());
        cartRepository.updateTotalPrice(cart.id(), 0.0);

        return new OrderDto(savedOrder, orderHistoryItemDtos);
    }


    private CartEntity getRequiredCart(Long customerId) {
        CartEntity cart = cartRepository.getByCustomerId(customerId);
        if (cart == null) {
            throw new ResourceNotFoundException("Cart not found");
        }
        return cart;
    }


    private CartEntity getRequiredLockedCart(Long customerId) {
        CartEntity cart = cartRepository.lockByCustomerId(customerId);
        if (cart == null) {
            throw new ResourceNotFoundException("Cart not found");
        }
        return cart;
    }


    private MenuItemEntity getRequiredMenuItem(Long menuItemId) {
        return menuItemRepository.findById(menuItemId)
                .orElseThrow(() -> new ResourceNotFoundException("Menu item not found"));
    }


    private OrderItemEntity findOwnedOrderItem(Long cartId, Long orderItemId) {
        OrderItemEntity orderItem = orderItemRepository.findById(orderItemId).orElse(null);
        if (orderItem == null || !orderItem.cartId().equals(cartId)) {
            return null;
        }
        return orderItem;
    }


    private CartDto buildCartDto(CartEntity cart) {
        List<OrderItemEntity> orderItems = orderItemRepository.getAllByCartId(cart.id());
        List<OrderItemDto> orderItemDtos = new ArrayList<>();
        for (OrderItemEntity orderItem : orderItems) {
            MenuItemEntity menuItem = getRequiredMenuItem(orderItem.menuItemId());
            orderItemDtos.add(new OrderItemDto(orderItem, menuItem));
        }
        return new CartDto(cart, orderItemDtos);
    }


    private void syncCartTotal(Long cartId) {
        Double totalPrice = orderItemRepository.getTotalPriceByCartId(cartId);
        cartRepository.updateTotalPrice(cartId, totalPrice);
    }


    private OrderDto getRequiredOrderDto(Long orderId) {
        OrderEntity order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        List<OrderHistoryItemEntity> orderItems = orderHistoryItemRepository.findAllByOrderIdOrderByIdAsc(order.id());
        List<OrderHistoryItemDto> itemDtos = new ArrayList<>();
        for (OrderHistoryItemEntity orderItem : orderItems) {
            itemDtos.add(new OrderHistoryItemDto(orderItem));
        }
        return new OrderDto(order, itemDtos);
    }


    private String normalizeStatus(String status) {
        return OrderStatus.normalizeForCheckout(status).name();
    }


    private String normalizeIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BadRequestException("Idempotency-Key header is required");
        }
        return idempotencyKey.trim();
    }


    private String buildScope(String status) {
        return "checkout:" + status.toLowerCase();
    }


    private String buildDefaultRequestHash(String status) {
        return "checkout|" + normalizeStatus(status);
    }


    private String buildCheckoutRequestHash(CartEntity cart, String requestHash, String status) {
        List<CheckoutItemSnapshot> cartItems = orderItemRepository.getAllByCartId(cart.id()).stream()
                .map(cartItem -> new CheckoutItemSnapshot(cartItem.menuItemId(), cartItem.quantity(), cartItem.price()))
                .toList();
        return buildCheckoutRequestHash(cart.id(), requestHash, status, cartItems);
    }


    private String buildCheckoutRequestHashForOrder(Long cartId, String requestHash, String status, Long orderId) {
        List<CheckoutItemSnapshot> orderItems = orderHistoryItemRepository.findAllByOrderIdOrderByIdAsc(orderId).stream()
                .map(orderItem -> new CheckoutItemSnapshot(orderItem.menuItemId(), orderItem.quantity(), orderItem.price()))
                .toList();
        return buildCheckoutRequestHash(cartId, requestHash, status, orderItems);
    }


    private String buildCheckoutRequestHash(Long cartId, String requestHash, String status, List<CheckoutItemSnapshot> itemSnapshots) {
        List<CheckoutItemSnapshot> sortedItemSnapshots = new ArrayList<>(itemSnapshots);
        sortedItemSnapshots.sort(Comparator
                .comparing(CheckoutItemSnapshot::menuItemId)
                .thenComparing(CheckoutItemSnapshot::quantity)
                .thenComparing(CheckoutItemSnapshot::price));

        StringBuilder normalizedPayload = new StringBuilder()
                .append(status)
                .append('|')
                .append(requestHash)
                .append('|')
                .append(cartId);
        for (CheckoutItemSnapshot itemSnapshot : sortedItemSnapshots) {
            normalizedPayload.append('|')
                    .append(itemSnapshot.menuItemId())
                    .append(':')
                    .append(itemSnapshot.quantity())
                    .append(':')
                    .append(itemSnapshot.price());
        }

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(normalizedPayload.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }


    private record CheckoutItemSnapshot(Long menuItemId, Integer quantity, Double price) {
    }
}
