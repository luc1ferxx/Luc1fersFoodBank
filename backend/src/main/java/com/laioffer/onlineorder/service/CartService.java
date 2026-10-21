package com.laioffer.onlineorder.service;


import com.laioffer.onlineorder.entity.CartEntity;
import com.laioffer.onlineorder.entity.IdempotencyRequestEntity;
import com.laioffer.onlineorder.entity.MenuItemEntity;
import com.laioffer.onlineorder.entity.OrderEntity;
import com.laioffer.onlineorder.entity.OrderHistoryItemEntity;
import com.laioffer.onlineorder.entity.OrderItemEntity;
import com.laioffer.onlineorder.entity.OrderPaymentEntity;
import com.laioffer.onlineorder.exception.BadRequestException;
import com.laioffer.onlineorder.exception.ConflictException;
import com.laioffer.onlineorder.exception.ResourceNotFoundException;
import com.laioffer.onlineorder.model.CartDto;
import com.laioffer.onlineorder.model.OrderActor;
import com.laioffer.onlineorder.model.OrderDto;
import com.laioffer.onlineorder.model.OrderHistoryItemDto;
import com.laioffer.onlineorder.model.OrderItemDto;
import com.laioffer.onlineorder.model.OrderStatus;
import com.laioffer.onlineorder.money.Money;
import com.laioffer.onlineorder.money.MoneyOverflowException;
import com.laioffer.onlineorder.observability.ApplicationMetricsService;
import com.laioffer.onlineorder.repository.CartRepository;
import com.laioffer.onlineorder.repository.IdempotencyRequestRepository;
import com.laioffer.onlineorder.repository.MenuItemRepository;
import com.laioffer.onlineorder.repository.OrderHistoryItemRepository;
import com.laioffer.onlineorder.repository.OrderItemRepository;
import com.laioffer.onlineorder.repository.OrderPaymentRepository;
import com.laioffer.onlineorder.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;


@Service
public class CartService {

    private static final Logger LOGGER = LoggerFactory.getLogger(CartService.class);

    private static final String IDEMPOTENCY_STATUS_PROCESSING = "PROCESSING";
    private static final String IDEMPOTENCY_STATUS_SUCCEEDED = "SUCCEEDED";

    public static final String CART_RESTAURANT_CONFLICT = "CART_RESTAURANT_CONFLICT";
    public static final String CART_HAS_MULTIPLE_RESTAURANTS = "CART_HAS_MULTIPLE_RESTAURANTS";
    public static final String CART_TOTAL_TOO_LARGE = "CART_TOTAL_TOO_LARGE";

    private final CartRepository cartRepository;
    private final IdempotencyRequestRepository idempotencyRequestRepository;
    private final IdempotencyRequestInitializer idempotencyRequestInitializer;
    private final MenuItemRepository menuItemRepository;
    private final OrderItemRepository orderItemRepository;
    private final CartItemQuantityUpdater cartItemQuantityUpdater;
    private final OrderRepository orderRepository;
    private final OrderHistoryItemRepository orderHistoryItemRepository;
    private final OrderEventOutboxService orderEventOutboxService;
    private final OrderStatusHistoryService orderStatusHistoryService;
    private final OrderPaymentRepository orderPaymentRepository;
    private final ApplicationMetricsService metricsService;


    public CartService(
            CartRepository cartRepository,
            IdempotencyRequestRepository idempotencyRequestRepository,
            IdempotencyRequestInitializer idempotencyRequestInitializer,
            MenuItemRepository menuItemRepository,
            OrderItemRepository orderItemRepository,
            CartItemQuantityUpdater cartItemQuantityUpdater,
            OrderRepository orderRepository,
            OrderHistoryItemRepository orderHistoryItemRepository,
            OrderEventOutboxService orderEventOutboxService,
            OrderStatusHistoryService orderStatusHistoryService,
            OrderPaymentRepository orderPaymentRepository,
            ApplicationMetricsService metricsService
    ) {
        this.cartRepository = cartRepository;
        this.idempotencyRequestRepository = idempotencyRequestRepository;
        this.idempotencyRequestInitializer = idempotencyRequestInitializer;
        this.menuItemRepository = menuItemRepository;
        this.orderItemRepository = orderItemRepository;
        this.cartItemQuantityUpdater = cartItemQuantityUpdater;
        this.orderRepository = orderRepository;
        this.orderHistoryItemRepository = orderHistoryItemRepository;
        this.orderEventOutboxService = orderEventOutboxService;
        this.orderStatusHistoryService = orderStatusHistoryService;
        this.orderPaymentRepository = orderPaymentRepository;
        this.metricsService = metricsService;
    }


    /**
     * A cart only holds items of one restaurant, so that every order goes to exactly one merchant. Adding an item of
     * another restaurant fails with 409 CART_RESTAURANT_CONFLICT; the customer clears the cart (or checks it out)
     * first. The cart row lock serializes concurrent adds, so two requests cannot fill one cart from two restaurants.
     *
     * <p>For the same reason a cart is single-currency: it takes the currency of its restaurant when its first item is
     * added, and the line keeps the item's price in that currency. There is no separate currency conflict: the
     * restaurant conflict comes first, and a cart whose lines are all of this restaurant is already in its currency.
     */
    @Transactional
    public void addMenuItemToCart(long customerId, long menuItemId) {
        CartEntity cart = getRequiredLockedCart(customerId);
        MenuItemEntity menuItem = getRequiredMenuItem(menuItemId);
        if (!orderItemRepository.findOtherRestaurantIdsInCart(cart.id(), menuItem.restaurantId()).isEmpty()) {
            throw new ConflictException(
                    CART_RESTAURANT_CONFLICT,
                    "Your cart already has items from another restaurant. Clear the cart or check it out before "
                            + "adding items from this restaurant."
            );
        }
        Money unitPrice = menuItem.unitPrice();
        switchCurrencyOfEmptyCart(cart, unitPrice.currencyCode());
        cartItemQuantityUpdater.incrementQuantity(cart.id(), menuItem.id(), unitPrice);
        syncCartTotal(cart.id(), unitPrice.currencyCode());
    }


    /**
     * Moves an empty cart to {@code currency}. A cart with lines is already in it, so a mismatch there is a broken
     * invariant, not a client error: the caller has ruled out lines of another restaurant, the lines of this one are in
     * its menu items' currency, which is the restaurant's (composite keys), and a cart line has its cart's currency
     * (another composite key).
     *
     * @throws IllegalStateException when a cart with lines is in another currency than its restaurant
     */
    private void switchCurrencyOfEmptyCart(CartEntity cart, String currency) {
        if (cart.currency().equals(currency)) {
            return;
        }
        if (!orderItemRepository.getAllByCartId(cart.id()).isEmpty()) {
            throw new IllegalStateException("Cart " + cart.id() + " holds lines in " + cart.currency()
                    + " and none of another restaurant, but the restaurant prices in " + currency);
        }
        Money emptyTotal = Money.zero(currency);
        cartRepository.updateTotal(cart.id(), emptyTotal.amount(), emptyTotal.currencyCode());
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
                syncCartTotal(cart.id(), cart.currency());
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
                    quantity,
                    orderItem.currency()
            );
            orderItemRepository.save(updatedOrderItem);
        }

        syncCartTotal(cart.id(), cart.currency());
    }


    /**
     * Empties the customer's cart (DELETE /cart), for example to switch restaurants after a 409
     * CART_RESTAURANT_CONFLICT. Takes the cart row lock like every other cart mutation, so it serializes with
     * concurrent adds and checkouts, and recomputes the total from the remaining lines (none). Clearing an empty
     * cart is a no-op, so retries are safe. An account without a cart row (one provisioned without a cart, such as
     * the SQL-seeded demo merchants) has nothing to clear: that is the same success, not a 404.
     */
    @Transactional
    public void clearCart(Long customerId) {
        CartEntity cart = cartRepository.lockByCustomerId(customerId);
        if (cart == null) {
            return;
        }
        orderItemRepository.deleteByCartId(cart.id());
        syncCartTotal(cart.id(), cart.currency());
    }


    @Transactional
    public OrderDto checkout(Long customerId) {
        return recordCheckoutAttempt(customerId, "PLACED", () -> {
            CartEntity cart = getRequiredLockedCart(customerId);
            return checkoutInternal(customerId, cart, "PLACED", null);
        });
    }


    @Transactional
    public OrderDto checkout(Long customerId, String status) {
        return recordCheckoutAttempt(customerId, status, () -> {
            CartEntity cart = getRequiredLockedCart(customerId);
            return checkoutInternal(customerId, cart, status, null);
        });
    }


    @Transactional
    public OrderDto checkoutWithIdempotency(Long customerId, String status, String idempotencyKey) {
        return checkoutWithIdempotency(customerId, status, idempotencyKey, buildDefaultRequestHash(status));
    }


    @Transactional
    public OrderDto checkoutWithIdempotency(Long customerId, String status, String idempotencyKey, String requestHash) {
        return recordCheckoutAttempt(customerId, status, () -> {
            String normalizedStatus = normalizeStatus(status);
            String normalizedKey = normalizeIdempotencyKey(idempotencyKey);
            CartEntity cart = getRequiredLockedCart(customerId);
            String effectiveRequestHash = buildCheckoutRequestHash(cart, requestHash, normalizedStatus);
            LocalDateTime now = LocalDateTime.now();
            String scope = buildScope(normalizedStatus);

            idempotencyRequestInitializer.insertIfAbsent(
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
        });
    }


    /**
     * Turns the locked cart into an order. The order total is the exact sum of the line totals (price snapshot times
     * quantity) in the cart's currency, and the order and its items take that currency; a line in another currency
     * would fail the sum ({@link Money#plus}).
     *
     * <p>An order created PAID (the payment checkout, {@link PaymentService}) gets its payment capture recorded in
     * the same transaction ({@link #recordCapture}), so the order and its payment commit or roll back together. A
     * PLACED order has no payment, and an idempotent replay returns the stored order before this method, so an order
     * never gets a second capture.
     */
    private OrderDto checkoutInternal(Long customerId, CartEntity cart, String status, String idempotencyKey) {
        List<OrderItemEntity> cartItems = orderItemRepository.getAllByCartId(cart.id());
        if (cartItems.isEmpty()) {
            throw new BadRequestException("Your cart is empty");
        }

        Map<Long, MenuItemEntity> menuItemsById = getRequiredMenuItemsById(cartItems);
        Long restaurantId = requireSingleRestaurant(menuItemsById);
        Money total = cartTotal(cart.currency(), cartItems);

        LocalDateTime createdAt = LocalDateTime.now();
        OrderStatus initialStatus = OrderStatus.normalizeForCheckout(status);
        OrderEntity savedOrder = orderRepository.save(new OrderEntity(
                null,
                customerId,
                total,
                initialStatus.name(),
                createdAt,
                restaurantId,
                null
        ));
        orderStatusHistoryService.record(
                savedOrder.id(),
                null,
                initialStatus,
                OrderActor.customer(customerId),
                null,
                createdAt
        );
        if (initialStatus == OrderStatus.PAID) {
            recordCapture(savedOrder, total, createdAt);
        }

        List<OrderHistoryItemEntity> orderHistoryItems = new ArrayList<>();
        List<OrderHistoryItemDto> orderHistoryItemDtos = new ArrayList<>();
        for (OrderItemEntity cartItem : cartItems) {
            MenuItemEntity menuItem = menuItemsById.get(cartItem.menuItemId());
            Money unitPrice = cartItem.unitPrice();
            OrderHistoryItemEntity orderHistoryItem = new OrderHistoryItemEntity(
                    null,
                    savedOrder.id(),
                    cartItem.menuItemId(),
                    menuItem.restaurantId(),
                    unitPrice.amount(),
                    cartItem.quantity(),
                    menuItem.name(),
                    menuItem.description(),
                    menuItem.imageUrl(),
                    unitPrice.currencyCode()
            );
            orderHistoryItems.add(orderHistoryItem);
        }

        for (OrderHistoryItemEntity savedItem : orderHistoryItemRepository.saveAll(orderHistoryItems)) {
            orderHistoryItemDtos.add(new OrderHistoryItemDto(savedItem));
        }

        orderEventOutboxService.enqueueOrderEvent(savedOrder, orderHistoryItemDtos, idempotencyKey, savedOrder.createdAt());
        orderItemRepository.deleteByCartId(cart.id());
        Money emptyTotal = Money.zero(total.currency());
        cartRepository.updateTotal(cart.id(), emptyTotal.amount(), emptyTotal.currencyCode());

        return new OrderDto(savedOrder, orderHistoryItemDtos);
    }


    /**
     * Records the capture of the order's total (ADR 0002 D16), which the order's refunds are made against. Payments
     * are simulated: the card was only validated ({@link PaymentService}), and the provider reference is a new
     * {@code SIMPAY-} id.
     */
    private void recordCapture(OrderEntity order, Money total, LocalDateTime capturedAt) {
        orderPaymentRepository.save(new OrderPaymentEntity(
                null,
                order.id(),
                order.customerId(),
                total.amount(),
                total.currencyCode(),
                OrderPaymentEntity.CAPTURED,
                "SIMPAY-" + UUID.randomUUID().toString().replace("-", ""),
                capturedAt
        ));
    }


    /**
     * Carts filled before single-restaurant carts can still mix restaurants; such a cart cannot be checked out as one
     * order, so checkout fails with 409 and leaves the cart untouched for the customer to split.
     */
    private Long requireSingleRestaurant(Map<Long, MenuItemEntity> menuItemsById) {
        List<Long> restaurantIds = menuItemsById.values().stream()
                .map(MenuItemEntity::restaurantId)
                .distinct()
                .toList();
        if (restaurantIds.size() != 1) {
            throw new ConflictException(
                    CART_HAS_MULTIPLE_RESTAURANTS,
                    "Your cart has items from more than one restaurant. Remove the items of all but one restaurant "
                            + "before checking out."
            );
        }
        return restaurantIds.get(0);
    }


    private OrderDto recordCheckoutAttempt(Long customerId, String requestedStatus, Supplier<OrderDto> action) {
        try {
            OrderDto order = action.get();
            metricsService.recordCheckoutSuccess(order.status());
            LOGGER.info(
                    "Checkout completed: customer_id={}, order_id={}, status={}, total_price={}",
                    customerId,
                    order.id(),
                    order.status(),
                    order.totalPrice()
            );
            return order;
        } catch (RuntimeException ex) {
            metricsService.recordCheckoutFailure(ex.getClass().getSimpleName());
            LOGGER.warn(
                    "Checkout failed: customer_id={}, requested_status={}, reason={}, message={}",
                    customerId,
                    requestedStatus,
                    ex.getClass().getSimpleName(),
                    ex.getMessage()
            );
            throw ex;
        }
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
        Map<Long, MenuItemEntity> menuItemsById = getRequiredMenuItemsById(orderItems);
        for (OrderItemEntity orderItem : orderItems) {
            MenuItemEntity menuItem = menuItemsById.get(orderItem.menuItemId());
            orderItemDtos.add(new OrderItemDto(orderItem, menuItem));
        }
        return new CartDto(cart, orderItemDtos);
    }


    /**
     * Recomputes the stored cart total from the cart's lines after a change.
     */
    private void syncCartTotal(Long cartId, String currency) {
        Money total = cartTotal(currency, orderItemRepository.getAllByCartId(cartId));
        cartRepository.updateTotal(cartId, total.amount(), total.currencyCode());
    }


    /**
     * The exact sum of the line totals in {@code currency}.
     *
     * @throws BadRequestException (CART_TOTAL_TOO_LARGE) when the total exceeds the largest storable amount
     */
    private static Money cartTotal(String currency, List<OrderItemEntity> lines) {
        Money total = Money.zero(currency);
        try {
            for (OrderItemEntity line : lines) {
                total = total.plus(line.lineTotal());
            }
        } catch (MoneyOverflowException ex) {
            throw new BadRequestException(
                    CART_TOTAL_TOO_LARGE,
                    "The cart total would exceed " + Money.MAX_AMOUNT.toPlainString() + " " + currency
                            + ". Lower the quantity."
            );
        }
        return total;
    }


    private Map<Long, MenuItemEntity> getRequiredMenuItemsById(List<OrderItemEntity> orderItems) {
        List<Long> menuItemIds = orderItems.stream()
                .map(OrderItemEntity::menuItemId)
                .distinct()
                .toList();
        if (menuItemIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, MenuItemEntity> menuItemsById = new HashMap<>();
        for (MenuItemEntity menuItem : menuItemRepository.findAllById(menuItemIds)) {
            menuItemsById.put(menuItem.id(), menuItem);
        }
        for (Long menuItemId : menuItemIds) {
            if (!menuItemsById.containsKey(menuItemId)) {
                throw new ResourceNotFoundException("Menu item not found");
            }
        }
        return menuItemsById;
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
                    .append(formatAmountForHash(itemSnapshot.price()));
        }

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(normalizedPayload.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }


    private String formatAmountForHash(BigDecimal amount) {
        return amount.stripTrailingZeros().toPlainString();
    }


    private record CheckoutItemSnapshot(Long menuItemId, Integer quantity, BigDecimal price) {
    }
}
