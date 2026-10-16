package com.laioffer.onlineorder.fulfilment.merchant;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.laioffer.onlineorder.entity.CustomerEntity;
import com.laioffer.onlineorder.exception.ConflictException;
import com.laioffer.onlineorder.exception.ResourceNotFoundException;
import com.laioffer.onlineorder.fulfilment.order.OrderLifecycleCommands;
import com.laioffer.onlineorder.model.OrderDto;
import com.laioffer.onlineorder.model.OrderStatus;
import com.laioffer.onlineorder.observability.ApplicationMetricsService;
import com.laioffer.onlineorder.service.CustomerService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;


import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;


@ExtendWith(MockitoExtension.class)
class MerchantOrderServiceTests {

    private static final String MERCHANT_EMAIL = "merchant@example.com";
    private static final long MERCHANT_CUSTOMER_ID = 40L;
    private static final long RESTAURANT_ID = 2L;
    private static final long ORDER_ID = 10L;

    @Mock
    private CustomerService customerService;

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
                customerService,
                merchantAccountRepository,
                merchantOrderRepository,
                orderLifecycleCommands,
                metricsService,
                new ObjectMapper().findAndRegisterModules()
        );
    }


    @Test
    void accept_shouldTransitionTheOrderAndUpdateTheInboxInOneCommand() {
        bindMerchant();
        Mockito.when(merchantOrderRepository.lockByOrderIdAndRestaurantId(ORDER_ID, RESTAURANT_ID))
                .thenReturn(inboxOrder("PAID"));
        Mockito.when(orderLifecycleCommands.transitionRestaurantOrder(
                ORDER_ID, RESTAURANT_ID, OrderStatus.PAID, OrderStatus.ACCEPTED, null)).thenReturn(order("ACCEPTED", null));

        MerchantOrderDto accepted = service.accept(MERCHANT_EMAIL, ORDER_ID);

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

        Assertions.assertThrows(ResourceNotFoundException.class, () -> service.accept(MERCHANT_EMAIL, ORDER_ID));

        Mockito.verifyNoInteractions(orderLifecycleCommands, metricsService);
    }


    @Test
    void accept_whenLoginIsNotBoundToARestaurant_shouldBeForbidden() {
        Mockito.when(customerService.getCustomerByEmail(MERCHANT_EMAIL)).thenReturn(customer());
        Mockito.when(merchantAccountRepository.findByCustomerId(MERCHANT_CUSTOMER_ID)).thenReturn(null);

        Assertions.assertThrows(AccessDeniedException.class, () -> service.accept(MERCHANT_EMAIL, ORDER_ID));

        Mockito.verifyNoInteractions(merchantOrderRepository, orderLifecycleCommands);
    }


    @Test
    void accept_whenOrderAlreadyTimedOut_shouldPropagateTheConflictWithoutUpdatingTheInbox() {
        bindMerchant();
        Mockito.when(merchantOrderRepository.lockByOrderIdAndRestaurantId(ORDER_ID, RESTAURANT_ID))
                .thenReturn(inboxOrder("PAID"));
        Mockito.when(orderLifecycleCommands.transitionRestaurantOrder(
                        ORDER_ID, RESTAURANT_ID, OrderStatus.PAID, OrderStatus.ACCEPTED, null))
                .thenThrow(new ConflictException(OrderLifecycleCommands.ORDER_STATUS_CONFLICT, "Order is CANCELLED"));

        Assertions.assertThrows(ConflictException.class, () -> service.accept(MERCHANT_EMAIL, ORDER_ID));

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
                ORDER_ID, RESTAURANT_ID, OrderStatus.PAID, OrderStatus.ACCEPTED, null)).thenReturn(order("ACCEPTED", null));

        Assertions.assertEquals("ACCEPTED", service.accept(MERCHANT_EMAIL, ORDER_ID).status());

        Mockito.verifyNoInteractions(metricsService);
    }


    @Test
    void reject_shouldCancelWithTheMerchantsReason() {
        bindMerchant();
        Mockito.when(merchantOrderRepository.lockByOrderIdAndRestaurantId(ORDER_ID, RESTAURANT_ID))
                .thenReturn(inboxOrder("PAID"));
        Mockito.when(orderLifecycleCommands.transitionRestaurantOrder(
                        ORDER_ID, RESTAURANT_ID, OrderStatus.PAID, OrderStatus.CANCELLED, "MERCHANT_REJECTED: Sold out"))
                .thenReturn(order("CANCELLED", "MERCHANT_REJECTED: Sold out"));

        MerchantOrderDto rejected = service.reject(MERCHANT_EMAIL, ORDER_ID, "  Sold out ");

        Assertions.assertEquals("CANCELLED", rejected.status());
        Assertions.assertEquals("MERCHANT_REJECTED: Sold out", rejected.cancelReason());
        Mockito.verify(metricsService).recordMerchantDecision("rejected");
    }


    @Test
    void markPreparing_shouldRequireAnAcceptedOrder() {
        bindMerchant();
        Mockito.when(merchantOrderRepository.lockByOrderIdAndRestaurantId(ORDER_ID, RESTAURANT_ID))
                .thenReturn(inboxOrder("ACCEPTED"));
        Mockito.when(orderLifecycleCommands.transitionRestaurantOrder(
                ORDER_ID, RESTAURANT_ID, OrderStatus.ACCEPTED, OrderStatus.PREPARING, null)).thenReturn(order("PREPARING", null));

        Assertions.assertEquals("PREPARING", service.markPreparing(MERCHANT_EMAIL, ORDER_ID).status());
        Mockito.verifyNoInteractions(metricsService);
    }


    @Test
    void getOrders_shouldOnlyReadTheMerchantsRestaurant() {
        bindMerchant();
        Mockito.when(merchantOrderRepository.findPageByRestaurantIdAndStatus(RESTAURANT_ID, "PAID", 50))
                .thenReturn(List.of(inboxOrder("PAID")));

        List<MerchantOrderDto> orders = service.getOrders(MERCHANT_EMAIL, "paid", 50);

        Assertions.assertEquals(1, orders.size());
        Assertions.assertEquals(RESTAURANT_ID, orders.get(0).restaurantId());
        Mockito.verify(merchantOrderRepository, Mockito.never()).findPageByRestaurantId(Mockito.anyLong(), Mockito.anyInt());
    }


    private void bindMerchant() {
        Mockito.when(customerService.getCustomerByEmail(MERCHANT_EMAIL)).thenReturn(customer());
        Mockito.when(merchantAccountRepository.findByCustomerId(MERCHANT_CUSTOMER_ID))
                .thenReturn(new MerchantAccountEntity(1L, MERCHANT_CUSTOMER_ID, RESTAURANT_ID, LocalDateTime.now()));
    }


    private CustomerEntity customer() {
        return new CustomerEntity(MERCHANT_CUSTOMER_ID, MERCHANT_EMAIL, "{noop}x", true, "M", "M", "ACTIVE",
                true, 0, null, null, null, null);
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


    private OrderDto order(String status, String cancelReason) {
        return new OrderDto(ORDER_ID, new BigDecimal("10.59"), status, LocalDateTime.of(2026, 4, 4, 12, 0),
                List.of(), RESTAURANT_ID, cancelReason);
    }
}
