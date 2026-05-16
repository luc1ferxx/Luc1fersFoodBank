package com.laioffer.onlineorder;


import com.laioffer.onlineorder.model.CartDto;
import com.laioffer.onlineorder.model.OrderDto;
import com.laioffer.onlineorder.model.PaymentCheckoutBody;
import com.laioffer.onlineorder.service.CartService;
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
