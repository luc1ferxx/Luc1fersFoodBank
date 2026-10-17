package com.laioffer.onlineorder;


import com.laioffer.onlineorder.entity.CartEntity;
import com.laioffer.onlineorder.entity.IdempotencyRequestEntity;
import com.laioffer.onlineorder.entity.MenuItemEntity;
import com.laioffer.onlineorder.entity.OrderEntity;
import com.laioffer.onlineorder.entity.OrderHistoryItemEntity;
import com.laioffer.onlineorder.entity.OrderItemEntity;
import com.laioffer.onlineorder.exception.BadRequestException;
import com.laioffer.onlineorder.exception.ConflictException;
import com.laioffer.onlineorder.fulfilment.order.OrderActor;
import com.laioffer.onlineorder.model.CartDto;
import com.laioffer.onlineorder.model.OrderDto;
import com.laioffer.onlineorder.model.OrderStatus;
import com.laioffer.onlineorder.money.CurrencyMismatchException;
import com.laioffer.onlineorder.money.Money;
import com.laioffer.onlineorder.observability.ApplicationMetricsService;
import com.laioffer.onlineorder.repository.CartRepository;
import com.laioffer.onlineorder.repository.IdempotencyRequestRepository;
import com.laioffer.onlineorder.repository.MenuItemRepository;
import com.laioffer.onlineorder.repository.OrderHistoryItemRepository;
import com.laioffer.onlineorder.repository.OrderItemRepository;
import com.laioffer.onlineorder.repository.OrderRepository;
import com.laioffer.onlineorder.service.CartItemQuantityUpdater;
import com.laioffer.onlineorder.service.CartService;
import com.laioffer.onlineorder.service.IdempotencyRequestInitializer;
import com.laioffer.onlineorder.service.OrderEventOutboxService;
import com.laioffer.onlineorder.service.OrderStatusHistoryService;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;


@ExtendWith(MockitoExtension.class)
public class CartServiceTests {

    @Mock
    private CartRepository cartRepository;

    @Mock
    private IdempotencyRequestRepository idempotencyRequestRepository;

    @Mock
    private IdempotencyRequestInitializer idempotencyRequestInitializer;

    @Mock
    private MenuItemRepository menuItemRepository;

    @Mock
    private OrderItemRepository orderItemRepository;

    @Mock
    private CartItemQuantityUpdater cartItemQuantityUpdater;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderHistoryItemRepository orderHistoryItemRepository;

    @Mock
    private OrderEventOutboxService orderEventOutboxService;

    @Mock
    private OrderStatusHistoryService orderStatusHistoryService;

    @Mock
    private ApplicationMetricsService metricsService;

    private CartService cartService;


    @BeforeEach
    void setup() {
        cartService = new CartService(
                cartRepository,
                idempotencyRequestRepository,
                idempotencyRequestInitializer,
                menuItemRepository,
                orderItemRepository,
                cartItemQuantityUpdater,
                orderRepository,
                orderHistoryItemRepository,
                orderEventOutboxService,
                orderStatusHistoryService,
                metricsService
        );
    }


    @Test
    void addMenuItemToCart_shouldUseAtomicIncrementAndRefreshTotal() {
        long customerId = 1L;
        long menuItemId = 2L;
        long cartId = 3L;
        CartEntity cartEntity = new CartEntity(cartId, customerId, amount("0.0"), "USD");
        MenuItemEntity menuItem = new MenuItemEntity(menuItemId, 1L, "Name", "", amount("10.0"), "", "USD");

        Mockito.when(cartRepository.lockByCustomerId(customerId)).thenReturn(cartEntity);
        Mockito.when(menuItemRepository.findById(menuItemId)).thenReturn(Optional.of(menuItem));
        Mockito.when(orderItemRepository.getAllByCartId(cartId))
                .thenReturn(List.of(new OrderItemEntity(7L, menuItemId, cartId, amount("10.0"), 1, "USD")));

        cartService.addMenuItemToCart(customerId, menuItemId);

        Mockito.verify(cartItemQuantityUpdater).incrementQuantity(cartId, menuItemId, usd("10.00"));
        Mockito.verify(cartRepository).updateTotal(cartId, amount("10.00"), "USD");
    }


    @Test
    void addMenuItemToCart_whenCartHoldsAnotherRestaurant_shouldRejectWithCartRestaurantConflict() {
        long customerId = 1L;
        long menuItemId = 2L;
        long cartId = 3L;
        CartEntity cartEntity = new CartEntity(cartId, customerId, amount("10.0"), "USD");
        MenuItemEntity otherRestaurantItem = new MenuItemEntity(menuItemId, 7L, "Tofu", "", amount("17.0"), "", "USD");

        Mockito.when(cartRepository.lockByCustomerId(customerId)).thenReturn(cartEntity);
        Mockito.when(menuItemRepository.findById(menuItemId)).thenReturn(Optional.of(otherRestaurantItem));
        Mockito.when(orderItemRepository.findOtherRestaurantIdsInCart(cartId, 7L)).thenReturn(List.of(1L));

        ConflictException conflict = Assertions.assertThrows(
                ConflictException.class,
                () -> cartService.addMenuItemToCart(customerId, menuItemId)
        );

        Assertions.assertEquals(CartService.CART_RESTAURANT_CONFLICT, conflict.getCode());
        Mockito.verifyNoInteractions(cartItemQuantityUpdater);
        Mockito.verify(cartRepository, Mockito.never()).updateTotal(Mockito.anyLong(), Mockito.any(), Mockito.any());
    }


    @Test
    void addMenuItemToCart_whenCartHoldsTheSameRestaurant_shouldAddTheItem() {
        long customerId = 1L;
        long menuItemId = 2L;
        long cartId = 3L;
        CartEntity cartEntity = new CartEntity(cartId, customerId, amount("10.0"), "USD");
        MenuItemEntity sameRestaurantItem = new MenuItemEntity(menuItemId, 1L, "Fries", "", amount("4.0"), "", "USD");

        Mockito.when(cartRepository.lockByCustomerId(customerId)).thenReturn(cartEntity);
        Mockito.when(menuItemRepository.findById(menuItemId)).thenReturn(Optional.of(sameRestaurantItem));
        Mockito.when(orderItemRepository.findOtherRestaurantIdsInCart(cartId, 1L)).thenReturn(List.of());
        Mockito.when(orderItemRepository.getAllByCartId(cartId)).thenReturn(List.of(
                new OrderItemEntity(6L, 1L, cartId, amount("10.0"), 1, "USD"),
                new OrderItemEntity(7L, menuItemId, cartId, amount("4.0"), 1, "USD")
        ));

        cartService.addMenuItemToCart(customerId, menuItemId);

        Mockito.verify(cartItemQuantityUpdater).incrementQuantity(cartId, menuItemId, usd("4.00"));
        Mockito.verify(cartRepository).updateTotal(cartId, amount("14.00"), "USD");
    }


    @Test
    void checkout_shouldRecordTheRestaurantOfTheCartOnTheOrder() {
        long customerId = 1L;
        long cartId = 2L;
        long menuItemId = 3L;
        CartEntity cartEntity = new CartEntity(cartId, customerId, amount("10.0"), "USD");
        OrderItemEntity cartItem = new OrderItemEntity(5L, menuItemId, cartId, amount("10.0"), 1, "USD");
        MenuItemEntity menuItem = new MenuItemEntity(menuItemId, 9L, "Burger", "Classic burger", amount("10.0"), "image", "USD");

        Mockito.when(cartRepository.lockByCustomerId(customerId)).thenReturn(cartEntity);
        Mockito.when(orderItemRepository.getAllByCartId(cartId)).thenReturn(List.of(cartItem));
        Mockito.when(menuItemRepository.findAllById(List.of(menuItemId))).thenReturn(List.of(menuItem));
        Mockito.when(orderRepository.save(Mockito.any(OrderEntity.class)))
                .thenAnswer(invocation -> {
                    OrderEntity order = invocation.getArgument(0);
                    return new OrderEntity(4L, order.customerId(), order.total(), order.status(),
                            order.createdAt(), order.restaurantId(), order.cancelReason());
                });
        Mockito.when(orderHistoryItemRepository.saveAll(Mockito.anyIterable())).thenReturn(List.of());

        OrderDto orderDto = cartService.checkout(customerId, "PAID");

        ArgumentCaptor<OrderEntity> savedOrder = ArgumentCaptor.forClass(OrderEntity.class);
        Mockito.verify(orderRepository).save(savedOrder.capture());
        Assertions.assertEquals(9L, savedOrder.getValue().restaurantId());
        Assertions.assertNull(savedOrder.getValue().cancelReason());
        Assertions.assertEquals(9L, orderDto.restaurantId());
        Mockito.verify(orderEventOutboxService).enqueueOrderEvent(
                Mockito.argThat(order -> Long.valueOf(9L).equals(order.restaurantId())),
                Mockito.anyList(),
                Mockito.isNull(),
                Mockito.any()
        );
        // The creation is the order's first history entry, by the customer who checked out.
        Assertions.assertEquals(savedOrder.getValue().createdAt(), savedOrder.getValue().updatedAt());
        Mockito.verify(orderStatusHistoryService).record(
                4L,
                null,
                OrderStatus.PAID,
                OrderActor.customer(customerId),
                null,
                savedOrder.getValue().createdAt()
        );
    }


    @Test
    void checkout_whenCartMixesRestaurants_shouldRejectWithoutCreatingAnOrder() {
        long customerId = 1L;
        long cartId = 2L;
        CartEntity cartEntity = new CartEntity(cartId, customerId, amount("27.0"), "USD");
        List<OrderItemEntity> cartItems = List.of(
                new OrderItemEntity(5L, 3L, cartId, amount("10.0"), 1, "USD"),
                new OrderItemEntity(6L, 4L, cartId, amount("17.0"), 1, "USD")
        );
        List<MenuItemEntity> menuItems = List.of(
                new MenuItemEntity(3L, 1L, "Burger", "", amount("10.0"), "", "USD"),
                new MenuItemEntity(4L, 2L, "Tofu", "", amount("17.0"), "", "USD")
        );

        Mockito.when(cartRepository.lockByCustomerId(customerId)).thenReturn(cartEntity);
        Mockito.when(orderItemRepository.getAllByCartId(cartId)).thenReturn(cartItems);
        Mockito.when(menuItemRepository.findAllById(List.of(3L, 4L))).thenReturn(menuItems);

        ConflictException conflict = Assertions.assertThrows(
                ConflictException.class,
                () -> cartService.checkout(customerId)
        );

        Assertions.assertEquals(CartService.CART_HAS_MULTIPLE_RESTAURANTS, conflict.getCode());
        Mockito.verify(orderRepository, Mockito.never()).save(Mockito.any());
        Mockito.verify(orderItemRepository, Mockito.never()).deleteByCartId(Mockito.anyLong());
        Mockito.verifyNoInteractions(orderEventOutboxService);
        Mockito.verify(metricsService).recordCheckoutFailure("ConflictException");
    }


    @Test
    void getCart_shouldReturnCartDto() {
        long customerId = 1L;
        long cartId = 3L;
        CartEntity cartEntity = new CartEntity(cartId, customerId, amount("21.0"), "USD");
        List<OrderItemEntity> orderItems = List.of(
                new OrderItemEntity(1L, 1L, cartId, amount("1.0"), 1, "USD"),
                new OrderItemEntity(2L, 2L, cartId, amount("10.0"), 2, "USD")
        );
        List<MenuItemEntity> menuItems = List.of(
                new MenuItemEntity(1L, 1L, "Name1", "", amount("1.0"), "", "USD"),
                new MenuItemEntity(2L, 1L, "Name2", "", amount("10.0"), "", "USD")
        );

        Mockito.when(cartRepository.getByCustomerId(customerId)).thenReturn(cartEntity);
        Mockito.when(orderItemRepository.getAllByCartId(cartEntity.id())).thenReturn(orderItems);
        Mockito.when(menuItemRepository.findAllById(List.of(1L, 2L))).thenReturn(menuItems);

        CartDto cartDto = cartService.getCart(customerId);

        Assertions.assertEquals(cartId, cartDto.id());
        Assertions.assertEquals(cartEntity.totalPrice(), cartDto.totalPrice());
        Assertions.assertEquals("USD", cartDto.currency());
        Assertions.assertEquals(orderItems.size(), cartDto.orderItems().size());
        Assertions.assertEquals("USD", cartDto.orderItems().get(1).currency());
    }


    @Test
    void clearCart_shouldRemoveAllItemsAndResetTotalPrice() {
        long customerId = 1L;
        long cartId = 2L;
        CartEntity cartEntity = new CartEntity(cartId, customerId, amount("21.0"), "USD");

        Mockito.when(cartRepository.lockByCustomerId(customerId)).thenReturn(cartEntity);
        Mockito.when(orderItemRepository.getAllByCartId(cartId)).thenReturn(List.of());

        cartService.clearCart(customerId);

        // Locked before anything is deleted, and the total recomputed from the (now empty) cart lines, in the
        // currency the cart keeps.
        InOrder inOrder = Mockito.inOrder(cartRepository, orderItemRepository);
        inOrder.verify(cartRepository).lockByCustomerId(customerId);
        inOrder.verify(orderItemRepository).deleteByCartId(cartId);
        inOrder.verify(orderItemRepository).getAllByCartId(cartId);
        inOrder.verify(cartRepository).updateTotal(cartId, amount("0.00"), "USD");
    }


    @Test
    void clearCart_whenTheCustomerHasNoCart_shouldSucceedWithoutChanges() {
        Mockito.when(cartRepository.lockByCustomerId(1L)).thenReturn(null);

        Assertions.assertDoesNotThrow(() -> cartService.clearCart(1L));
        Mockito.verifyNoInteractions(orderItemRepository);
        Mockito.verify(cartRepository, Mockito.never()).updateTotal(Mockito.anyLong(), Mockito.any(), Mockito.any());
    }


    @Test
    void updateOrderItemQuantity_whenDeletingMissingItem_shouldBeIdempotent() {
        long customerId = 1L;
        long cartId = 2L;
        CartEntity cartEntity = new CartEntity(cartId, customerId, amount("21.0"), "USD");

        Mockito.when(cartRepository.lockByCustomerId(customerId)).thenReturn(cartEntity);
        Mockito.when(orderItemRepository.findById(99L)).thenReturn(Optional.empty());
        Mockito.when(orderItemRepository.getAllByCartId(cartId)).thenReturn(List.of());

        cartService.updateOrderItemQuantity(customerId, 99L, 0);

        Mockito.verify(orderItemRepository, Mockito.never()).deleteByIdAndCartId(Mockito.anyLong(), Mockito.anyLong());
        Mockito.verify(cartRepository).updateTotal(cartId, amount("0.00"), "USD");
    }


    @Test
    void checkout_shouldCreateOrderAndClearCart() {
        long customerId = 1L;
        long cartId = 2L;
        long menuItemId = 3L;
        long orderId = 4L;
        CartEntity cartEntity = new CartEntity(cartId, customerId, amount("10.0"), "USD");
        OrderItemEntity cartItem = new OrderItemEntity(5L, menuItemId, cartId, amount("10.0"), 1, "USD");
        MenuItemEntity menuItem = new MenuItemEntity(menuItemId, 9L, "Burger", "Classic burger", amount("10.0"), "image", "USD");
        OrderEntity savedOrder = new OrderEntity(orderId, customerId, usd("10.0"), "PLACED", LocalDateTime.of(2026, 4, 4, 12, 0));
        OrderHistoryItemEntity savedHistoryItem = new OrderHistoryItemEntity(
                6L,
                orderId,
                menuItemId,
                9L,
                amount("10.0"),
                1,
                "Burger",
                "Classic burger",
                "image",
                "USD"
        );

        Mockito.when(cartRepository.lockByCustomerId(customerId)).thenReturn(cartEntity);
        Mockito.when(orderItemRepository.getAllByCartId(cartId)).thenReturn(List.of(cartItem));
        Mockito.when(menuItemRepository.findAllById(List.of(menuItemId))).thenReturn(List.of(menuItem));
        Mockito.when(orderRepository.save(Mockito.any(OrderEntity.class))).thenReturn(savedOrder);
        Mockito.when(orderHistoryItemRepository.saveAll(Mockito.anyIterable())).thenReturn(List.of(savedHistoryItem));

        OrderDto orderDto = cartService.checkout(customerId);

        Assertions.assertEquals(orderId, orderDto.id());
        Assertions.assertEquals("PLACED", orderDto.status());
        Assertions.assertEquals(1, orderDto.items().size());
        Mockito.verify(orderEventOutboxService).enqueueOrderEvent(savedOrder, orderDto.items(), null, savedOrder.createdAt());
        Mockito.verify(metricsService).recordCheckoutSuccess("PLACED");
        Mockito.verify(orderItemRepository).deleteByCartId(cartId);
        Mockito.verify(cartRepository).updateTotal(cartId, amount("0.00"), "USD");
    }


    @Test
    void checkout_whenCartIsEmpty_shouldRecordCheckoutFailure() {
        long customerId = 1L;
        long cartId = 2L;
        CartEntity cartEntity = new CartEntity(cartId, customerId, BigDecimal.ZERO, "USD");

        Mockito.when(cartRepository.lockByCustomerId(customerId)).thenReturn(cartEntity);
        Mockito.when(orderItemRepository.getAllByCartId(cartId)).thenReturn(List.of());

        Assertions.assertThrows(
                com.laioffer.onlineorder.exception.BadRequestException.class,
                () -> cartService.checkout(customerId)
        );

        Mockito.verify(metricsService).recordCheckoutFailure("BadRequestException");
        Mockito.verify(metricsService, Mockito.never()).recordCheckoutSuccess(Mockito.anyString());
    }


    @Test
    void checkoutWithIdempotency_whenRequestAlreadySucceeded_shouldReuseExistingOrder() {
        long customerId = 1L;
        long orderId = 4L;
        long cartId = 2L;
        long menuItemId = 3L;
        CartEntity cartEntity = new CartEntity(cartId, customerId, amount("10.0"), "USD");
        OrderItemEntity cartItem = new OrderItemEntity(5L, menuItemId, cartId, amount("10.0"), 1, "USD");
        IdempotencyRequestEntity request = new IdempotencyRequestEntity(
                10L,
                customerId,
                "checkout:paid",
                "key-1",
                hashCheckoutRequest(cartEntity, "hash-1", "PAID", cartItem),
                "SUCCEEDED",
                orderId,
                LocalDateTime.of(2026, 4, 4, 12, 0),
                LocalDateTime.of(2026, 4, 4, 12, 1)
        );
        OrderEntity savedOrder = new OrderEntity(orderId, customerId, usd("10.0"), "PAID", LocalDateTime.of(2026, 4, 4, 12, 0));
        OrderHistoryItemEntity savedHistoryItem = new OrderHistoryItemEntity(
                6L,
                orderId,
                menuItemId,
                9L,
                amount("10.0"),
                1,
                "Burger",
                "Classic burger",
                "image",
                "USD"
        );

        Mockito.lenient().when(cartRepository.lockByCustomerId(customerId)).thenReturn(cartEntity);
        Mockito.lenient().when(orderItemRepository.getAllByCartId(cartId)).thenReturn(List.of(cartItem));
        Mockito.when(idempotencyRequestRepository.lockByCustomerIdAndScopeAndIdempotencyKey(customerId, "checkout:paid", "key-1"))
                .thenReturn(request);
        Mockito.when(orderRepository.findById(orderId)).thenReturn(Optional.of(savedOrder));
        Mockito.when(orderHistoryItemRepository.findAllByOrderIdOrderByIdAsc(orderId)).thenReturn(List.of(savedHistoryItem));

        OrderDto orderDto = cartService.checkoutWithIdempotency(customerId, "PAID", "key-1", "hash-1");

        Assertions.assertEquals(orderId, orderDto.id());
        Mockito.verify(idempotencyRequestInitializer).insertIfAbsent(
                Mockito.eq(customerId),
                Mockito.eq("checkout:paid"),
                Mockito.eq("key-1"),
                Mockito.anyString(),
                Mockito.eq("PROCESSING"),
                Mockito.any(LocalDateTime.class),
                Mockito.any(LocalDateTime.class)
        );
        Mockito.verifyNoInteractions(orderEventOutboxService);
    }


    @Test
    void checkoutWithIdempotency_whenCartWasClearedAfterSuccess_shouldStillReuseExistingOrder() {
        long customerId = 1L;
        long orderId = 4L;
        long cartId = 2L;
        long menuItemId = 3L;
        CartEntity cartEntity = new CartEntity(cartId, customerId, BigDecimal.ZERO, "USD");
        OrderItemEntity originalCartItem = new OrderItemEntity(5L, menuItemId, cartId, amount("10.0"), 1, "USD");
        IdempotencyRequestEntity request = new IdempotencyRequestEntity(
                10L,
                customerId,
                "checkout:paid",
                "key-1",
                hashCheckoutRequest(cartEntity, "hash-1", "PAID", originalCartItem),
                "SUCCEEDED",
                orderId,
                LocalDateTime.of(2026, 4, 4, 12, 0),
                LocalDateTime.of(2026, 4, 4, 12, 1)
        );
        OrderEntity savedOrder = new OrderEntity(orderId, customerId, usd("10.0"), "PAID", LocalDateTime.of(2026, 4, 4, 12, 0));
        OrderHistoryItemEntity savedHistoryItem = new OrderHistoryItemEntity(
                6L,
                orderId,
                menuItemId,
                9L,
                amount("10.0"),
                1,
                "Burger",
                "Classic burger",
                "image",
                "USD"
        );

        Mockito.when(cartRepository.lockByCustomerId(customerId)).thenReturn(cartEntity);
        Mockito.when(orderItemRepository.getAllByCartId(cartId)).thenReturn(List.of());
        Mockito.when(idempotencyRequestRepository.lockByCustomerIdAndScopeAndIdempotencyKey(customerId, "checkout:paid", "key-1"))
                .thenReturn(request);
        Mockito.when(orderRepository.findById(orderId)).thenReturn(Optional.of(savedOrder));
        Mockito.when(orderHistoryItemRepository.findAllByOrderIdOrderByIdAsc(orderId)).thenReturn(List.of(savedHistoryItem));

        OrderDto orderDto = cartService.checkoutWithIdempotency(customerId, "PAID", "key-1", "hash-1");

        Assertions.assertEquals(orderId, orderDto.id());
        Mockito.verify(orderRepository).findById(orderId);
        Mockito.verifyNoInteractions(orderEventOutboxService);
    }


    @Test
    void checkoutWithIdempotency_whenCartChangesUnderSameKey_shouldConflict() {
        long customerId = 1L;
        long orderId = 4L;
        long cartId = 2L;
        CartEntity cartEntity = new CartEntity(cartId, customerId, amount("20.0"), "USD");
        OrderItemEntity changedCartItem = new OrderItemEntity(5L, 7L, cartId, amount("20.0"), 1, "USD");
        IdempotencyRequestEntity request = new IdempotencyRequestEntity(
                10L,
                customerId,
                "checkout:paid",
                "key-1",
                "hash-1",
                "SUCCEEDED",
                orderId,
                LocalDateTime.of(2026, 4, 4, 12, 0),
                LocalDateTime.of(2026, 4, 4, 12, 1)
        );

        Mockito.when(cartRepository.lockByCustomerId(customerId)).thenReturn(cartEntity);
        Mockito.when(orderItemRepository.getAllByCartId(cartId)).thenReturn(List.of(changedCartItem));
        Mockito.when(idempotencyRequestRepository.lockByCustomerIdAndScopeAndIdempotencyKey(customerId, "checkout:paid", "key-1"))
                .thenReturn(request);

        Assertions.assertThrows(
                com.laioffer.onlineorder.exception.ConflictException.class,
                () -> cartService.checkoutWithIdempotency(customerId, "PAID", "key-1", "hash-1")
        );
        Mockito.verify(orderRepository, Mockito.never()).findById(Mockito.anyLong());
        Mockito.verifyNoInteractions(orderEventOutboxService);
    }


    @Test
    void addMenuItemToCart_whenTheEmptyCartIsInAnotherCurrency_shouldMoveTheCartToTheItemsCurrencyFirst() {
        long customerId = 1L;
        long menuItemId = 2L;
        long cartId = 3L;
        CartEntity emptyUsdCart = new CartEntity(cartId, customerId, amount("0.00"), "USD");
        MenuItemEntity croissant = new MenuItemEntity(menuItemId, 8L, "Croissant", "", amount("3.50"), "", "EUR");

        Mockito.when(cartRepository.lockByCustomerId(customerId)).thenReturn(emptyUsdCart);
        Mockito.when(menuItemRepository.findById(menuItemId)).thenReturn(Optional.of(croissant));
        Mockito.when(orderItemRepository.findOtherRestaurantIdsInCart(cartId, 8L)).thenReturn(List.of());
        Mockito.when(orderItemRepository.getAllByCartId(cartId)).thenReturn(
                List.of(),
                List.of(new OrderItemEntity(7L, menuItemId, cartId, amount("3.50"), 1, "EUR"))
        );

        cartService.addMenuItemToCart(customerId, menuItemId);

        // The cart's currency changes while it is empty, before the line that the database ties to it is added.
        InOrder inOrder = Mockito.inOrder(cartRepository, cartItemQuantityUpdater);
        inOrder.verify(cartRepository).updateTotal(cartId, amount("0.00"), "EUR");
        inOrder.verify(cartItemQuantityUpdater).incrementQuantity(cartId, menuItemId, Money.of("3.50", "EUR"));
        inOrder.verify(cartRepository).updateTotal(cartId, amount("3.50"), "EUR");
    }


    @Test
    void addMenuItemToCart_whenTheCartHoldsLinesInAnotherCurrency_shouldRejectWithCartCurrencyConflict() {
        long customerId = 1L;
        long menuItemId = 2L;
        long cartId = 3L;
        CartEntity usdCart = new CartEntity(cartId, customerId, amount("10.00"), "USD");
        MenuItemEntity croissant = new MenuItemEntity(menuItemId, 8L, "Croissant", "", amount("3.50"), "", "EUR");

        Mockito.when(cartRepository.lockByCustomerId(customerId)).thenReturn(usdCart);
        Mockito.when(menuItemRepository.findById(menuItemId)).thenReturn(Optional.of(croissant));
        Mockito.when(orderItemRepository.findOtherRestaurantIdsInCart(cartId, 8L)).thenReturn(List.of());
        Mockito.when(orderItemRepository.getAllByCartId(cartId))
                .thenReturn(List.of(new OrderItemEntity(6L, 5L, cartId, amount("10.00"), 1, "USD")));

        ConflictException conflict = Assertions.assertThrows(
                ConflictException.class,
                () -> cartService.addMenuItemToCart(customerId, menuItemId)
        );

        Assertions.assertEquals(CartService.CART_CURRENCY_CONFLICT, conflict.getCode());
        Mockito.verifyNoInteractions(cartItemQuantityUpdater);
        Mockito.verify(cartRepository, Mockito.never()).updateTotal(Mockito.anyLong(), Mockito.any(), Mockito.any());
    }


    @Test
    void checkout_shouldTotalTheLinesExactlyInTheCartsCurrency() {
        long customerId = 1L;
        long cartId = 2L;
        CartEntity eurCart = new CartEntity(cartId, customerId, amount("0.50"), "EUR");
        // 3 x 0.10 + 0.20: 0.5000000000000001 in binary floating point, exactly 0.50 here.
        List<OrderItemEntity> cartItems = List.of(
                new OrderItemEntity(5L, 3L, cartId, amount("0.10"), 3, "EUR"),
                new OrderItemEntity(6L, 4L, cartId, amount("0.20"), 1, "EUR")
        );
        List<MenuItemEntity> menuItems = List.of(
                new MenuItemEntity(3L, 8L, "Mint", "", amount("0.10"), "", "EUR"),
                new MenuItemEntity(4L, 8L, "Sugar", "", amount("0.20"), "", "EUR")
        );

        Mockito.when(cartRepository.lockByCustomerId(customerId)).thenReturn(eurCart);
        Mockito.when(orderItemRepository.getAllByCartId(cartId)).thenReturn(cartItems);
        Mockito.when(menuItemRepository.findAllById(List.of(3L, 4L))).thenReturn(menuItems);
        Mockito.when(orderRepository.save(Mockito.any(OrderEntity.class)))
                .thenAnswer(invocation -> {
                    OrderEntity order = invocation.getArgument(0);
                    return new OrderEntity(9L, order.customerId(), order.total(), order.status(),
                            order.createdAt(), order.restaurantId(), order.cancelReason());
                });
        Mockito.when(orderHistoryItemRepository.saveAll(Mockito.anyIterable()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        OrderDto order = cartService.checkout(customerId, "PAID");

        Assertions.assertEquals(new BigDecimal("0.50"), order.totalPrice());
        Assertions.assertEquals("EUR", order.currency());
        Assertions.assertEquals(List.of("EUR", "EUR"), order.items().stream().map(item -> item.currency()).toList());
        Assertions.assertEquals(
                List.of(new BigDecimal("0.10"), new BigDecimal("0.20")),
                order.items().stream().map(item -> item.price()).toList()
        );
        // The emptied cart keeps its currency.
        Mockito.verify(cartRepository).updateTotal(cartId, amount("0.00"), "EUR");
    }


    @Test
    void checkout_whenALineIsInAnotherCurrencyThanItsCart_shouldFailWithoutCreatingAnOrder() {
        long customerId = 1L;
        long cartId = 2L;
        CartEntity usdCart = new CartEntity(cartId, customerId, amount("10.00"), "USD");
        List<OrderItemEntity> cartItems = List.of(
                new OrderItemEntity(5L, 3L, cartId, amount("10.00"), 1, "USD"),
                new OrderItemEntity(6L, 4L, cartId, amount("2.00"), 1, "EUR")
        );
        List<MenuItemEntity> menuItems = List.of(
                new MenuItemEntity(3L, 8L, "Burger", "", amount("10.00"), "", "USD"),
                new MenuItemEntity(4L, 8L, "Fries", "", amount("2.00"), "", "EUR")
        );

        Mockito.when(cartRepository.lockByCustomerId(customerId)).thenReturn(usdCart);
        Mockito.when(orderItemRepository.getAllByCartId(cartId)).thenReturn(cartItems);
        Mockito.when(menuItemRepository.findAllById(List.of(3L, 4L))).thenReturn(menuItems);

        Assertions.assertThrows(CurrencyMismatchException.class, () -> cartService.checkout(customerId));

        Mockito.verify(orderRepository, Mockito.never()).save(Mockito.any());
        Mockito.verifyNoInteractions(orderEventOutboxService);
        Mockito.verify(orderItemRepository, Mockito.never()).deleteByCartId(Mockito.anyLong());
    }


    @Test
    void updateOrderItemQuantity_whenTheTotalWouldExceedTheLargestAmount_shouldRejectWithCartTotalTooLarge() {
        long customerId = 1L;
        long cartId = 2L;
        CartEntity cartEntity = new CartEntity(cartId, customerId, amount("10.00"), "USD");
        OrderItemEntity line = new OrderItemEntity(5L, 3L, cartId, amount("10.00"), 1, "USD");

        Mockito.when(cartRepository.lockByCustomerId(customerId)).thenReturn(cartEntity);
        Mockito.when(orderItemRepository.findById(5L)).thenReturn(Optional.of(line));
        Mockito.when(orderItemRepository.getAllByCartId(cartId))
                .thenReturn(List.of(new OrderItemEntity(5L, 3L, cartId, amount("10.00"), 2_000_000_000, "USD")));

        BadRequestException rejected = Assertions.assertThrows(
                BadRequestException.class,
                () -> cartService.updateOrderItemQuantity(customerId, 5L, 2_000_000_000)
        );

        Assertions.assertEquals(CartService.CART_TOTAL_TOO_LARGE, rejected.getCode());
        Mockito.verify(cartRepository, Mockito.never()).updateTotal(Mockito.anyLong(), Mockito.any(), Mockito.any());
    }


    private String hashCheckoutRequest(CartEntity cart, String requestHash, String status, OrderItemEntity... cartItems) {
        StringBuilder normalizedPayload = new StringBuilder()
                .append(status)
                .append('|')
                .append(requestHash)
                .append('|')
                .append(cart.id());
        for (OrderItemEntity cartItem : cartItems) {
            normalizedPayload.append('|')
                    .append(cartItem.menuItemId())
                    .append(':')
                    .append(cartItem.quantity())
                    .append(':')
                    .append(cartItem.price().stripTrailingZeros().toPlainString());
        }

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(normalizedPayload.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }


    private BigDecimal amount(String value) {
        return new BigDecimal(value);
    }


    private static Money usd(String value) {
        return Money.of(value, "USD");
    }
}
