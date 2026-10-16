package com.laioffer.onlineorder.fulfilment.merchant;


import com.laioffer.onlineorder.exception.ConflictException;
import com.laioffer.onlineorder.exception.ResourceNotFoundException;
import com.laioffer.onlineorder.fulfilment.order.OrderActor;
import com.laioffer.onlineorder.fulfilment.order.OrderLifecycleCommands;
import com.laioffer.onlineorder.fulfilment.order.OrderLifecycleCommands.RestaurantOrderTransition;
import com.laioffer.onlineorder.model.OrderDto;
import com.laioffer.onlineorder.model.OrderStatus;
import com.laioffer.onlineorder.observability.ApplicationMetricsService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import tools.jackson.databind.json.JsonMapper;


import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;


@ExtendWith(MockitoExtension.class)
class MerchantOrderServiceTests {

    private static final long MERCHANT_CUSTOMER_ID = 40L;
    private static final long RESTAURANT_ID = 2L;
    private static final long ORDER_ID = 10L;
    private static final OrderActor MERCHANT = OrderActor.merchant(MERCHANT_CUSTOMER_ID);

    @Mock
    private MerchantAccountRepository merchantAccountRepository;

    @Mock
    private MerchantOrderRepository merchantOrderRepository;

    @Mock
    private OrderLifecycleCommands orderLifecycleCommands;

    @Mock
    private ApplicationMetricsService metricsService;

    private MerchantOrderService service;


    @BeforeEach
    void setup() {
        service = new MerchantOrderService(
                merchantAccountRepository,
                merchantOrderRepository,
                orderLifecycleCommands,
                metricsService,
                JsonMapper.builder().build()
        );
    }


    @Test
    void accept_shouldTransitionTheOrderAndUpdateTheInboxInOneCommand() {
        bindMerchant();
        Mockito.when(merchantOrderRepository.lockByOrderIdAndRestaurantId(ORDER_ID, RESTAURANT_ID))
                .thenReturn(inboxOrder("PAID"));
        Mockito.when(orderLifecycleCommands.transitionRestaurantOrder(
                ORDER_ID, RESTAURANT_ID, OrderStatus.PAID, OrderStatus.ACCEPTED, null, MERCHANT)).thenReturn(changed("ACCEPTED", null));

        MerchantOrderDto accepted = service.accept(MERCHANT_CUSTOMER_ID, ORDER_ID);

        Assertions.assertEquals("ACCEPTED", accepted.status());
        Assertions.assertEquals(1, accepted.items().size());
        Assertions.assertEquals("Whopper Meal", accepted.items().get(0).menuItemName());
        Mockito.verify(merchantOrderRepository).updateStatus(
                Mockito.eq(1L), Mockito.eq("ACCEPTED"), Mockito.isNull(), Mockito.any(LocalDateTime.class));
        Mockito.verify(metricsService).recordMerchantDecision("accepted");
    }


    @Test
    void accept_whenOrderIsNotInThisRestaurantsInbox_shouldReturnNotFoundWithoutTouchingTheOrder() {
        bindMerchant();
        Mockito.when(merchantOrderRepository.lockByOrderIdAndRestaurantId(ORDER_ID, RESTAURANT_ID)).thenReturn(null);

        Assertions.assertThrows(ResourceNotFoundException.class, () -> service.accept(MERCHANT_CUSTOMER_ID, ORDER_ID));

        Mockito.verifyNoInteractions(orderLifecycleCommands, metricsService);
    }


    @Test
    void accept_whenLoginIsNotBoundToARestaurant_shouldBeForbidden() {
        Mockito.when(merchantAccountRepository.findByCustomerId(MERCHANT_CUSTOMER_ID)).thenReturn(null);

        Assertions.assertThrows(AccessDeniedException.class, () -> service.accept(MERCHANT_CUSTOMER_ID, ORDER_ID));

        Mockito.verifyNoInteractions(merchantOrderRepository, orderLifecycleCommands);
    }


    @Test
    void everyCommand_whenTheMerchantAccountIsDeactivated_shouldBeForbidden() {
        Mockito.when(merchantAccountRepository.findByCustomerId(MERCHANT_CUSTOMER_ID)).thenReturn(new MerchantAccountEntity(
                1L, MERCHANT_CUSTOMER_ID, RESTAURANT_ID, false, LocalDateTime.now(), LocalDateTime.now()));

        Assertions.assertThrows(AccessDeniedException.class, () -> service.accept(MERCHANT_CUSTOMER_ID, ORDER_ID));
        Assertions.assertThrows(AccessDeniedException.class, () -> service.markPreparing(MERCHANT_CUSTOMER_ID, ORDER_ID));
        Assertions.assertThrows(
                AccessDeniedException.class, () -> service.reject(MERCHANT_CUSTOMER_ID, ORDER_ID, "closed"));
        Assertions.assertThrows(AccessDeniedException.class, () -> service.getOrders(MERCHANT_CUSTOMER_ID, null, 50));

        Mockito.verifyNoInteractions(merchantOrderRepository, orderLifecycleCommands);
    }


    @Test
    void accept_whenOrderAlreadyTimedOut_shouldPropagateTheConflictWithoutUpdatingTheInbox() {
        bindMerchant();
        Mockito.when(merchantOrderRepository.lockByOrderIdAndRestaurantId(ORDER_ID, RESTAURANT_ID))
                .thenReturn(inboxOrder("PAID"));
        Mockito.when(orderLifecycleCommands.transitionRestaurantOrder(
                        ORDER_ID, RESTAURANT_ID, OrderStatus.PAID, OrderStatus.ACCEPTED, null, MERCHANT))
                .thenThrow(new ConflictException(OrderLifecycleCommands.ORDER_STATUS_CONFLICT, "Order is CANCELLED"));

        Assertions.assertThrows(ConflictException.class, () -> service.accept(MERCHANT_CUSTOMER_ID, ORDER_ID));

        Mockito.verify(merchantOrderRepository, Mockito.never())
                .updateStatus(Mockito.anyLong(), Mockito.anyString(), Mockito.any(), Mockito.any());
        Mockito.verifyNoInteractions(metricsService);
    }


    @Test
    void accept_whenRepeated_shouldNotCountASecondDecision() {
        bindMerchant();
        Mockito.when(merchantOrderRepository.lockByOrderIdAndRestaurantId(ORDER_ID, RESTAURANT_ID))
                .thenReturn(inboxOrder("ACCEPTED"));
        Mockito.when(orderLifecycleCommands.transitionRestaurantOrder(
                ORDER_ID, RESTAURANT_ID, OrderStatus.PAID, OrderStatus.ACCEPTED, null, MERCHANT)).thenReturn(unchanged("ACCEPTED", null));

        Assertions.assertEquals("ACCEPTED", service.accept(MERCHANT_CUSTOMER_ID, ORDER_ID).status());

        Mockito.verifyNoInteractions(metricsService);
    }


    @Test
    void accept_whenAnAdminAcceptedTheOrderBeforeTheInboxCaughtUp_shouldNotCountADecision() {
        bindMerchant();
        // The inbox still says PAID: the merchant consumer has not seen order.accepted yet.
        Mockito.when(merchantOrderRepository.lockByOrderIdAndRestaurantId(ORDER_ID, RESTAURANT_ID))
                .thenReturn(inboxOrder("PAID"));
        Mockito.when(orderLifecycleCommands.transitionRestaurantOrder(
                ORDER_ID, RESTAURANT_ID, OrderStatus.PAID, OrderStatus.ACCEPTED, null, MERCHANT)).thenReturn(unchanged("ACCEPTED", null));

        Assertions.assertEquals("ACCEPTED", service.accept(MERCHANT_CUSTOMER_ID, ORDER_ID).status());

        Mockito.verify(merchantOrderRepository).updateStatus(
                Mockito.eq(1L), Mockito.eq("ACCEPTED"), Mockito.isNull(), Mockito.any(LocalDateTime.class));
        Mockito.verifyNoInteractions(metricsService);
    }


    @Test
    void reject_shouldCancelWithTheMerchantsReason() {
        bindMerchant();
        Mockito.when(merchantOrderRepository.lockByOrderIdAndRestaurantId(ORDER_ID, RESTAURANT_ID))
                .thenReturn(inboxOrder("PAID"));
        Mockito.when(orderLifecycleCommands.transitionRestaurantOrder(
                        ORDER_ID, RESTAURANT_ID, OrderStatus.PAID, OrderStatus.CANCELLED, "MERCHANT_REJECTED: Sold out", MERCHANT))
                .thenReturn(changed("CANCELLED", "MERCHANT_REJECTED: Sold out"));

        MerchantOrderDto rejected = service.reject(MERCHANT_CUSTOMER_ID, ORDER_ID, "  Sold out ");

        Assertions.assertEquals("CANCELLED", rejected.status());
        Assertions.assertEquals("MERCHANT_REJECTED: Sold out", rejected.cancelReason());
        Mockito.verify(metricsService).recordMerchantDecision("rejected");
    }


    @Test
    void reject_whenTheAcceptTimeoutCancelledTheOrderFirst_shouldConflictAndNotCountARejection() {
        bindMerchant();
        Mockito.when(merchantOrderRepository.lockByOrderIdAndRestaurantId(ORDER_ID, RESTAURANT_ID))
                .thenReturn(inboxOrder("PAID"));
        Mockito.when(orderLifecycleCommands.transitionRestaurantOrder(
                        ORDER_ID, RESTAURANT_ID, OrderStatus.PAID, OrderStatus.CANCELLED, "MERCHANT_REJECTED: Sold out", MERCHANT))
                .thenThrow(new ConflictException(
                        OrderLifecycleCommands.ORDER_STATUS_CONFLICT, "Order is CANCELLED (MERCHANT_TIMEOUT)"));

        Assertions.assertThrows(ConflictException.class, () -> service.reject(MERCHANT_CUSTOMER_ID, ORDER_ID, "Sold out"));

        Mockito.verify(merchantOrderRepository, Mockito.never())
                .updateStatus(Mockito.anyLong(), Mockito.anyString(), Mockito.any(), Mockito.any());
        Mockito.verifyNoInteractions(metricsService);
    }


    @Test
    void reject_whenRepeated_shouldReturnTheRejectionWithoutCountingItAgain() {
        bindMerchant();
        Mockito.when(merchantOrderRepository.lockByOrderIdAndRestaurantId(ORDER_ID, RESTAURANT_ID))
                .thenReturn(inboxOrder("CANCELLED"));
        Mockito.when(orderLifecycleCommands.transitionRestaurantOrder(
                        ORDER_ID, RESTAURANT_ID, OrderStatus.PAID, OrderStatus.CANCELLED, "MERCHANT_REJECTED: Sold out", MERCHANT))
                .thenReturn(unchanged("CANCELLED", "MERCHANT_REJECTED: Out of buns"));

        MerchantOrderDto rejected = service.reject(MERCHANT_CUSTOMER_ID, ORDER_ID, "Sold out");

        Assertions.assertEquals("MERCHANT_REJECTED: Out of buns", rejected.cancelReason());
        Mockito.verifyNoInteractions(metricsService);
    }


    @Test
    void markPreparing_shouldRequireAnAcceptedOrder() {
        bindMerchant();
        Mockito.when(merchantOrderRepository.lockByOrderIdAndRestaurantId(ORDER_ID, RESTAURANT_ID))
                .thenReturn(inboxOrder("ACCEPTED"));
        Mockito.when(orderLifecycleCommands.transitionRestaurantOrder(
                ORDER_ID, RESTAURANT_ID, OrderStatus.ACCEPTED, OrderStatus.PREPARING, null, MERCHANT)).thenReturn(changed("PREPARING", null));

        Assertions.assertEquals("PREPARING", service.markPreparing(MERCHANT_CUSTOMER_ID, ORDER_ID).status());
        Mockito.verifyNoInteractions(metricsService);
    }


    @Test
    void getOrders_shouldOnlyReadTheMerchantsRestaurant() {
        bindMerchant();
        Mockito.when(merchantOrderRepository.findPageByRestaurantIdAndStatus(RESTAURANT_ID, "PAID", 50))
                .thenReturn(List.of(inboxOrder("PAID")));

        List<MerchantOrderDto> orders = service.getOrders(MERCHANT_CUSTOMER_ID, "paid", 50);

        Assertions.assertEquals(1, orders.size());
        Assertions.assertEquals(RESTAURANT_ID, orders.get(0).restaurantId());
        Mockito.verify(merchantOrderRepository, Mockito.never()).findPageByRestaurantId(Mockito.anyLong(), Mockito.anyInt());
    }


    private void bindMerchant() {
        Mockito.when(merchantAccountRepository.findByCustomerId(MERCHANT_CUSTOMER_ID))
                .thenReturn(new MerchantAccountEntity(1L, MERCHANT_CUSTOMER_ID, RESTAURANT_ID, true, LocalDateTime.now(), LocalDateTime.now()));
    }


    private MerchantOrderEntity inboxOrder(String status) {
        LocalDateTime paidAt = LocalDateTime.of(2026, 4, 4, 12, 0);
        return new MerchantOrderEntity(
                1L,
                ORDER_ID,
                RESTAURANT_ID,
                3L,
                status,
                new BigDecimal("10.59"),
                "[{\"id\":1,\"menuItemId\":2,\"restaurantId\":2,\"price\":10.59,\"quantity\":1,\"menuItemName\":\"Whopper Meal\"}]",
                null,
                paidAt,
                paidAt,
                paidAt
        );
    }


    private RestaurantOrderTransition changed(String status, String cancelReason) {
        return new RestaurantOrderTransition(order(status, cancelReason), true);
    }


    private RestaurantOrderTransition unchanged(String status, String cancelReason) {
        return new RestaurantOrderTransition(order(status, cancelReason), false);
    }


    private OrderDto order(String status, String cancelReason) {
        return new OrderDto(ORDER_ID, new BigDecimal("10.59"), status, LocalDateTime.of(2026, 4, 4, 12, 0),
                List.of(), RESTAURANT_ID, cancelReason);
    }
}
