package com.laioffer.onlineorder;


import com.laioffer.onlineorder.entity.CartEntity;
import com.laioffer.onlineorder.entity.IdempotencyRequestEntity;
import com.laioffer.onlineorder.entity.MenuItemEntity;
import com.laioffer.onlineorder.entity.OrderEntity;
import com.laioffer.onlineorder.entity.OrderHistoryItemEntity;
import com.laioffer.onlineorder.entity.OrderItemEntity;
import com.laioffer.onlineorder.model.CartDto;
import com.laioffer.onlineorder.model.OrderDto;
import com.laioffer.onlineorder.observability.ApplicationMetricsService;
import com.laioffer.onlineorder.repository.CartRepository;
import com.laioffer.onlineorder.repository.IdempotencyRequestRepository;
import com.laioffer.onlineorder.repository.MenuItemRepository;
import com.laioffer.onlineorder.repository.OrderHistoryItemRepository;
import com.laioffer.onlineorder.repository.OrderItemRepository;
import com.laioffer.onlineorder.repository.OrderRepository;
import com.laioffer.onlineorder.service.CartService;
import com.laioffer.onlineorder.service.OrderEventOutboxService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;


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
    private MenuItemRepository menuItemRepository;

    @Mock
    private OrderItemRepository orderItemRepository;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderHistoryItemRepository orderHistoryItemRepository;

    @Mock
    private OrderEventOutboxService orderEventOutboxService;

    @Mock
    private ApplicationMetricsService metricsService;

    private CartService cartService;


    @BeforeEach
    void setup() {
        cartService = new CartService(
                cartRepository,
                idempotencyRequestRepository,
                menuItemRepository,
                orderItemRepository,
                orderRepository,
                orderHistoryItemRepository,
                orderEventOutboxService,
                metricsService
        );
    }


    @Test
    void addMenuItemToCart_shouldUseAtomicIncrementAndRefreshTotal() {
        long customerId = 1L;
        long menuItemId = 2L;
        long cartId = 3L;
        CartEntity cartEntity = new CartEntity(cartId, customerId, 0.0);
        MenuItemEntity menuItem = new MenuItemEntity(menuItemId, 1L, "Name", "", 10.0, "");

        Mockito.when(cartRepository.lockByCustomerId(customerId)).thenReturn(cartEntity);
        Mockito.when(menuItemRepository.findById(menuItemId)).thenReturn(Optional.of(menuItem));
        Mockito.when(orderItemRepository.getTotalPriceByCartId(cartId)).thenReturn(10.0);

        cartService.addMenuItemToCart(customerId, menuItemId);

        Mockito.verify(orderItemRepository).incrementQuantity(cartId, menuItemId, 10.0);
        Mockito.verify(cartRepository).updateTotalPrice(cartId, 10.0);
    }


    @Test
    void getCart_shouldReturnCartDto() {
        long customerId = 1L;
        long cartId = 3L;
        CartEntity cartEntity = new CartEntity(cartId, customerId, 21.0);
        List<OrderItemEntity> orderItems = List.of(
                new OrderItemEntity(1L, 1L, cartId, 1.0, 1),
                new OrderItemEntity(2L, 2L, cartId, 10.0, 2)
        );
        List<MenuItemEntity> menuItems = List.of(
                new MenuItemEntity(1L, 1L, "Name1", "", 1.0, ""),
                new MenuItemEntity(2L, 1L, "Name2", "", 10.0, "")
        );

        Mockito.when(cartRepository.getByCustomerId(customerId)).thenReturn(cartEntity);
        Mockito.when(orderItemRepository.getAllByCartId(cartEntity.id())).thenReturn(orderItems);
        Mockito.when(menuItemRepository.findById(1L)).thenReturn(Optional.of(menuItems.get(0)));
        Mockito.when(menuItemRepository.findById(2L)).thenReturn(Optional.of(menuItems.get(1)));

        CartDto cartDto = cartService.getCart(customerId);

        Assertions.assertEquals(cartId, cartDto.id());
        Assertions.assertEquals(cartEntity.totalPrice(), cartDto.totalPrice());
        Assertions.assertEquals(orderItems.size(), cartDto.orderItems().size());
    }


    @Test
    void clearCart_shouldRemoveAllItemsAndResetTotalPrice() {
        long customerId = 1L;
        long cartId = 2L;
        CartEntity cartEntity = new CartEntity(cartId, customerId, 21.0);

        Mockito.when(cartRepository.lockByCustomerId(customerId)).thenReturn(cartEntity);

        cartService.clearCart(customerId);

        Mockito.verify(orderItemRepository).deleteByCartId(cartId);
        Mockito.verify(cartRepository).updateTotalPrice(cartId, 0.0);
    }


    @Test
    void updateOrderItemQuantity_whenDeletingMissingItem_shouldBeIdempotent() {
        long customerId = 1L;
        long cartId = 2L;
        CartEntity cartEntity = new CartEntity(cartId, customerId, 21.0);

        Mockito.when(cartRepository.lockByCustomerId(customerId)).thenReturn(cartEntity);
        Mockito.when(orderItemRepository.findById(99L)).thenReturn(Optional.empty());
        Mockito.when(orderItemRepository.getTotalPriceByCartId(cartId)).thenReturn(0.0);

        cartService.updateOrderItemQuantity(customerId, 99L, 0);

        Mockito.verify(orderItemRepository, Mockito.never()).deleteByIdAndCartId(Mockito.anyLong(), Mockito.anyLong());
        Mockito.verify(cartRepository).updateTotalPrice(cartId, 0.0);
    }


    @Test
    void checkout_shouldCreateOrderAndClearCart() {
        long customerId = 1L;
        long cartId = 2L;
        long menuItemId = 3L;
        long orderId = 4L;
        CartEntity cartEntity = new CartEntity(cartId, customerId, 10.0);
        OrderItemEntity cartItem = new OrderItemEntity(5L, menuItemId, cartId, 10.0, 1);
        MenuItemEntity menuItem = new MenuItemEntity(menuItemId, 9L, "Burger", "Classic burger", 10.0, "image");
        OrderEntity savedOrder = new OrderEntity(orderId, customerId, 10.0, "PLACED", LocalDateTime.of(2026, 4, 4, 12, 0));
        OrderHistoryItemEntity savedHistoryItem = new OrderHistoryItemEntity(
                6L,
                orderId,
                menuItemId,
                9L,
                10.0,
                1,
                "Burger",
                "Classic burger",
                "image"
        );

        Mockito.when(cartRepository.lockByCustomerId(customerId)).thenReturn(cartEntity);
        Mockito.when(cartRepository.getByCustomerId(customerId)).thenReturn(cartEntity);
        Mockito.when(orderItemRepository.getAllByCartId(cartId)).thenReturn(List.of(cartItem));
        Mockito.when(orderItemRepository.getTotalPriceByCartId(cartId)).thenReturn(10.0);
        Mockito.when(menuItemRepository.findById(menuItemId)).thenReturn(Optional.of(menuItem));
        Mockito.when(orderRepository.save(Mockito.any(OrderEntity.class))).thenReturn(savedOrder);
        Mockito.when(orderHistoryItemRepository.saveAll(Mockito.anyIterable())).thenReturn(List.of(savedHistoryItem));

        OrderDto orderDto = cartService.checkout(customerId);

        Assertions.assertEquals(orderId, orderDto.id());
        Assertions.assertEquals("PLACED", orderDto.status());
        Assertions.assertEquals(1, orderDto.items().size());
        Mockito.verify(orderEventOutboxService).enqueueOrderEvent(savedOrder, orderDto.items(), null);
        Mockito.verify(metricsService).recordCheckout("PLACED");
        Mockito.verify(orderItemRepository).deleteByCartId(cartId);
        Mockito.verify(cartRepository).updateTotalPrice(cartId, 0.0);
    }


    @Test
    void checkoutWithIdempotency_whenRequestAlreadySucceeded_shouldReuseExistingOrder() {
        long customerId = 1L;
        long orderId = 4L;
        long cartId = 2L;
        long menuItemId = 3L;
        CartEntity cartEntity = new CartEntity(cartId, customerId, 10.0);
        OrderItemEntity cartItem = new OrderItemEntity(5L, menuItemId, cartId, 10.0, 1);
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
        OrderEntity savedOrder = new OrderEntity(orderId, customerId, 10.0, "PAID", LocalDateTime.of(2026, 4, 4, 12, 0));
        OrderHistoryItemEntity savedHistoryItem = new OrderHistoryItemEntity(
                6L,
                orderId,
                menuItemId,
                9L,
                10.0,
                1,
                "Burger",
                "Classic burger",
                "image"
        );

        Mockito.lenient().when(cartRepository.lockByCustomerId(customerId)).thenReturn(cartEntity);
        Mockito.lenient().when(orderItemRepository.getAllByCartId(cartId)).thenReturn(List.of(cartItem));
        Mockito.when(idempotencyRequestRepository.lockByCustomerIdAndScopeAndIdempotencyKey(customerId, "checkout:paid", "key-1"))
                .thenReturn(request);
        Mockito.when(orderRepository.findById(orderId)).thenReturn(Optional.of(savedOrder));
        Mockito.when(orderHistoryItemRepository.findAllByOrderIdOrderByIdAsc(orderId)).thenReturn(List.of(savedHistoryItem));

        OrderDto orderDto = cartService.checkoutWithIdempotency(customerId, "PAID", "key-1", "hash-1");

        Assertions.assertEquals(orderId, orderDto.id());
        Mockito.verify(idempotencyRequestRepository).insertIfAbsent(
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
        CartEntity cartEntity = new CartEntity(cartId, customerId, 0.0);
        OrderItemEntity originalCartItem = new OrderItemEntity(5L, menuItemId, cartId, 10.0, 1);
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
        OrderEntity savedOrder = new OrderEntity(orderId, customerId, 10.0, "PAID", LocalDateTime.of(2026, 4, 4, 12, 0));
        OrderHistoryItemEntity savedHistoryItem = new OrderHistoryItemEntity(
                6L,
                orderId,
                menuItemId,
                9L,
                10.0,
                1,
                "Burger",
                "Classic burger",
                "image"
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
        CartEntity cartEntity = new CartEntity(cartId, customerId, 20.0);
        OrderItemEntity changedCartItem = new OrderItemEntity(5L, 7L, cartId, 20.0, 1);
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
                    .append(cartItem.price());
        }

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(normalizedPayload.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
