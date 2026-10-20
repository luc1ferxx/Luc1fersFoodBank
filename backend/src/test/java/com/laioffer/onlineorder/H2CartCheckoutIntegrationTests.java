package com.laioffer.onlineorder;


import com.laioffer.onlineorder.exception.ConflictException;
import com.laioffer.onlineorder.model.CartDto;
import com.laioffer.onlineorder.model.OrderDto;
import com.laioffer.onlineorder.model.PaymentCheckoutBody;
import com.laioffer.onlineorder.service.CartService;
import com.laioffer.onlineorder.service.OrderService;
import com.laioffer.onlineorder.service.PaymentService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;


import java.math.BigDecimal;
import java.util.Map;


@SpringBootTest
@ActiveProfiles("h2")
class H2CartCheckoutIntegrationTests {

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.session.store-type", () -> "none");
        registry.add("spring.cache.type", () -> "none");
        registry.add("app.kafka.enabled", () -> "false");
        registry.add("app.outbox.publisher-enabled", () -> "false");
        registry.add("app.cleanup.enabled", () -> "false");
        registry.add("app.security.rate-limit.enabled", () -> "false");
    }

    @Autowired
    private CartService cartService;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private OrderService orderService;

    @Autowired
    private JdbcTemplate jdbcTemplate;


    @Test
    void h2Profile_shouldAddCartItemsAndCompletePaidCheckout() {
        Long customerId = customerId("demo@laifood.com");

        cartService.addMenuItemToCart(customerId, 1L);
        cartService.addMenuItemToCart(customerId, 1L);

        CartDto cart = cartService.getCart(customerId);
        Assertions.assertEquals(1, cart.orderItems().size());
        Assertions.assertEquals(2, cart.orderItems().get(0).quantity());
        Assertions.assertEquals(0, amount("9.78").compareTo(cart.totalPrice()));

        OrderDto paidOrder = paymentService.payAndCheckout(
                customerId,
                "h2-checkout-key-" + System.nanoTime(),
                new PaymentCheckoutBody(
                        "Demo User",
                        "4242424242424242",
                        "12/30",
                        "123"
                )
        );

        Assertions.assertEquals("PAID", paidOrder.status());
        Assertions.assertEquals(0, amount("9.78").compareTo(paidOrder.totalPrice()));
        Assertions.assertEquals(1, paidOrder.items().size());
        Assertions.assertEquals(2, paidOrder.items().get(0).quantity());
        Assertions.assertTrue(cartService.getCart(customerId).orderItems().isEmpty());
    }

    @Test
    void h2Profile_shouldKeepTheCartToOneRestaurantAndRecordItOnTheOrder() {
        Long customerId = customerId("demo@laifood.com");
        cartService.clearCart(customerId);
        Long burgerRestaurantId = restaurantIdOfMenuItem(1L);
        Long otherRestaurantItemId = jdbcTemplate.queryForObject(
                "SELECT MIN(id) FROM menu_items WHERE restaurant_id <> ?",
                Long.class,
                burgerRestaurantId
        );

        try {
            cartService.addMenuItemToCart(customerId, 1L);
            ConflictException conflict = Assertions.assertThrows(
                    ConflictException.class,
                    () -> cartService.addMenuItemToCart(customerId, otherRestaurantItemId)
            );
            Assertions.assertEquals(CartService.CART_RESTAURANT_CONFLICT, conflict.getCode());
            Assertions.assertEquals(1, cartService.getCart(customerId).orderItems().size());

            OrderDto order = cartService.checkout(customerId);

            Assertions.assertEquals(burgerRestaurantId, order.restaurantId());
            Assertions.assertEquals(burgerRestaurantId, jdbcTemplate.queryForObject(
                    "SELECT restaurant_id FROM orders WHERE id = ?",
                    Long.class,
                    order.id()
            ));

            // An empty cart accepts any restaurant again.
            cartService.addMenuItemToCart(customerId, otherRestaurantItemId);
        } finally {
            cartService.clearCart(customerId);
        }
    }


    @Test
    void paymentCheckout_shouldRecordOneCaptureThatNeitherAReplayNorAnotherCheckoutAdds() {
        Long customerId = customerId("demo@laifood.com");
        cartService.clearCart(customerId);
        PaymentCheckoutBody card = new PaymentCheckoutBody("Demo User", "4242424242424242", "12/30", "123");
        String key = "h2-capture-key-" + System.nanoTime();

        cartService.addMenuItemToCart(customerId, 1L);
        cartService.addMenuItemToCart(customerId, 1L);
        OrderDto paid = paymentService.payAndCheckout(customerId, key, card);

        Map<String, Object> capture = jdbcTemplate.queryForMap(
                "SELECT customer_id, amount, currency, status, provider_reference, captured_at FROM order_payments "
                        + "WHERE order_id = ?",
                paid.id()
        );
        Assertions.assertEquals(customerId, ((Number) capture.get("customer_id")).longValue());
        Assertions.assertEquals(0, paid.totalPrice().compareTo((BigDecimal) capture.get("amount")));
        Assertions.assertEquals(0, amount("9.78").compareTo((BigDecimal) capture.get("amount")));
        Assertions.assertEquals(paid.currency(), capture.get("currency"));
        Assertions.assertEquals("CAPTURED", capture.get("status"));
        Assertions.assertTrue(((String) capture.get("provider_reference")).startsWith("SIMPAY-"));
        Assertions.assertNotNull(capture.get("captured_at"));

        // A replay of the key returns the same order and captures nothing more.
        Assertions.assertEquals(paid.id(), paymentService.payAndCheckout(customerId, key, card).id());
        Assertions.assertEquals(1, countPayments(paid.id()));

        // POST /cart/checkout places an unpaid order, and the admin PATCH to PAID records no capture either.
        cartService.addMenuItemToCart(customerId, 1L);
        OrderDto placed = cartService.checkoutWithIdempotency(customerId, "PLACED", "h2-placed-key-" + System.nanoTime());
        Assertions.assertEquals("PLACED", placed.status());
        Assertions.assertEquals("PAID", orderService.idempotentTransitionOrderStatus(
                customerId, placed.id(), "PAID", "h2-admin-paid-key-" + System.nanoTime()).status());
        Assertions.assertEquals(0, countPayments(placed.id()));
        Assertions.assertEquals(1, countPayments(paid.id()));
    }


    private int countPayments(Long orderId) {
        Integer payments = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM order_payments WHERE order_id = ?",
                Integer.class,
                orderId
        );
        return payments == null ? 0 : payments;
    }


    private Long restaurantIdOfMenuItem(Long menuItemId) {
        return jdbcTemplate.queryForObject("SELECT restaurant_id FROM menu_items WHERE id = ?", Long.class, menuItemId);
    }


    private Long customerId(String email) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM customers WHERE email = ?",
                Long.class,
                email
        );
    }


    private BigDecimal amount(String value) {
        return new BigDecimal(value);
    }
}
