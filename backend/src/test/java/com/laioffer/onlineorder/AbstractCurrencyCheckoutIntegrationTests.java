package com.laioffer.onlineorder;


import com.laioffer.onlineorder.exception.ConflictException;
import com.laioffer.onlineorder.model.CartDto;
import com.laioffer.onlineorder.model.OrderDto;
import com.laioffer.onlineorder.model.OrderHistoryItemDto;
import com.laioffer.onlineorder.model.OrderItemDto;
import com.laioffer.onlineorder.model.PaymentCheckoutBody;
import com.laioffer.onlineorder.service.CartService;
import com.laioffer.onlineorder.service.CustomerService;
import com.laioffer.onlineorder.service.PaymentService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;


import java.math.BigDecimal;
import java.util.List;


/**
 * Checkout with currencies against a real database: a cart and its order are in the currency of their restaurant,
 * with totals summed exactly; the order event carries that currency; an empty cart moves to the currency of the next
 * restaurant; the database refuses a cart line in another currency, an unsupported currency and a yen price with
 * cents. Subclasses pick the database.
 */
@SpringBootTest(properties = {
        "spring.session.store-type=none",
        "spring.cache.type=none",
        "app.kafka.enabled=false",
        "app.outbox.publisher-enabled=false",
        "app.cleanup.enabled=false",
        "app.security.rate-limit.enabled=false"
})
abstract class AbstractCurrencyCheckoutIntegrationTests {

    private static final PaymentCheckoutBody CARD = new PaymentCheckoutBody("Euro Tester", "4242424242424242", "12/30", "123");

    @Autowired
    private CartService cartService;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private CustomerService customerService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;


    @Test
    void aEuroRestaurant_shouldBeOrderedFromInEuroWithExactTotals() {
        long customerId = signUp();
        long restaurantId = insertRestaurant("EUR");
        long mint = insertMenuItem(restaurantId, "Mint", "0.10", "EUR");
        long sugar = insertMenuItem(restaurantId, "Sugar", "0.20", "EUR");

        for (int i = 0; i < 3; i++) {
            cartService.addMenuItemToCart(customerId, mint);
        }
        cartService.addMenuItemToCart(customerId, sugar);

        // 3 x 0.10 + 0.20, summed exactly at scale 2.
        CartDto cart = cartService.getCart(customerId);
        Assertions.assertEquals(new BigDecimal("0.50"), cart.totalPrice());
        Assertions.assertEquals("EUR", cart.currency());
        Assertions.assertEquals(List.of("EUR", "EUR"), cart.orderItems().stream().map(OrderItemDto::currency).toList());

        OrderDto order = paymentService.payAndCheckout(customerId, "euro-" + System.nanoTime(), CARD);

        Assertions.assertEquals(new BigDecimal("0.50"), order.totalPrice());
        Assertions.assertEquals("EUR", order.currency());
        Assertions.assertEquals(List.of("EUR", "EUR"), order.items().stream().map(OrderHistoryItemDto::currency).toList());
        Assertions.assertEquals("EUR", jdbcTemplate.queryForObject(
                "SELECT currency FROM orders WHERE id = ?", String.class, order.id()));
        Assertions.assertEquals(List.of("EUR"), jdbcTemplate.queryForList(
                "SELECT DISTINCT currency FROM order_history_items WHERE order_id = ?", String.class, order.id()));

        // The order event carries the currency once, for the total and the line prices.
        JsonNode data = objectMapper.readTree(jdbcTemplate.queryForObject(
                "SELECT payload FROM outbox_events WHERE aggregate_id = ? AND event_type = 'order.paid'",
                String.class,
                order.id()
        )).get("data");
        Assertions.assertEquals("EUR", data.get("currency").asString());
        Assertions.assertEquals(0, new BigDecimal("0.50").compareTo(data.get("total_price").decimalValue()));
        Assertions.assertEquals(2, data.get("items").size());
        for (JsonNode item : data.get("items")) {
            Assertions.assertFalse(item.has("currency"), item.toString());
        }

        // The emptied cart keeps its currency.
        CartDto emptied = cartService.getCart(customerId);
        Assertions.assertEquals(new BigDecimal("0.00"), emptied.totalPrice());
        Assertions.assertEquals("EUR", emptied.currency());
    }


    @Test
    void anEmptyCart_shouldTakeTheCurrencyOfTheNextRestaurant() {
        long customerId = signUp();
        long croissant = insertMenuItem(insertRestaurant("EUR"), "Croissant", "3.50", "EUR");
        long bagel = insertMenuItem(insertRestaurant("USD"), "Bagel", "2.25", "USD");

        cartService.addMenuItemToCart(customerId, croissant);
        Assertions.assertEquals("EUR", cartService.getCart(customerId).currency());
        // A second restaurant (here also a second currency) needs an empty cart first.
        ConflictException conflict = Assertions.assertThrows(
                ConflictException.class,
                () -> cartService.addMenuItemToCart(customerId, bagel)
        );
        Assertions.assertEquals(CartService.CART_RESTAURANT_CONFLICT, conflict.getCode());

        cartService.clearCart(customerId);
        cartService.addMenuItemToCart(customerId, bagel);
        cartService.addMenuItemToCart(customerId, bagel);

        CartDto cart = cartService.getCart(customerId);
        Assertions.assertEquals("USD", cart.currency());
        Assertions.assertEquals(new BigDecimal("4.50"), cart.totalPrice());
        Assertions.assertEquals("USD", cart.orderItems().get(0).currency());
    }


    @Test
    void aYenRestaurant_shouldHaveOnlyWholePricesAndBeOrderedFromInYen() {
        long customerId = signUp();
        long restaurantId = insertRestaurant("JPY");
        // The database refuses what Money would reject later, on the customer's add-to-cart or checkout.
        Assertions.assertThrows(DataIntegrityViolationException.class,
                () -> insertMenuItem(restaurantId, "Half Ramen", "950.50", "JPY"));
        Assertions.assertThrows(DataIntegrityViolationException.class, () -> insertRestaurant("BHD"));
        Assertions.assertThrows(DataIntegrityViolationException.class, () -> insertRestaurant("ABC"));
        long ramen = insertMenuItem(restaurantId, "Ramen", "950", "JPY");

        cartService.addMenuItemToCart(customerId, ramen);
        cartService.addMenuItemToCart(customerId, ramen);

        CartDto cart = cartService.getCart(customerId);
        Assertions.assertEquals("JPY", cart.currency());
        Assertions.assertEquals(new BigDecimal("1900.00"), cart.totalPrice());
        OrderDto order = paymentService.payAndCheckout(customerId, "yen-" + System.nanoTime(), CARD);
        Assertions.assertEquals("JPY", order.currency());
        Assertions.assertEquals(new BigDecimal("1900.00"), order.totalPrice());
    }


    @Test
    void theDatabase_shouldRefuseACartLineInAnotherCurrencyThanItsCart() {
        long customerId = signUp();
        long bagel = insertMenuItem(insertRestaurant("USD"), "Bagel", "2.25", "USD");
        long croissant = insertMenuItem(insertRestaurant("EUR"), "Croissant", "3.50", "EUR");
        cartService.addMenuItemToCart(customerId, bagel);
        Long cartId = jdbcTemplate.queryForObject("SELECT id FROM carts WHERE customer_id = ?", Long.class, customerId);

        Assertions.assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
                "INSERT INTO order_items (menu_item_id, cart_id, price, quantity, currency) VALUES (?, ?, 3.50, 1, 'EUR')",
                croissant,
                cartId
        ));
        Assertions.assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
                "UPDATE carts SET currency = 'EUR' WHERE id = ?", cartId));
        Assertions.assertEquals("USD", cartService.getCart(customerId).currency());
    }


    private long signUp() {
        String email = "currency-" + System.nanoTime() + "@example.com";
        customerService.signUp(email, "secret123", "Currency", "Tester");
        return jdbcTemplate.queryForObject("SELECT id FROM customers WHERE email = ?", Long.class, email);
    }


    private long insertRestaurant(String currency) {
        String name = "Currency Test " + currency + " " + System.nanoTime();
        jdbcTemplate.update("INSERT INTO restaurants (name, currency) VALUES (?, ?)", name, currency);
        return jdbcTemplate.queryForObject("SELECT id FROM restaurants WHERE name = ?", Long.class, name);
    }


    private long insertMenuItem(long restaurantId, String name, String price, String currency) {
        jdbcTemplate.update(
                "INSERT INTO menu_items (restaurant_id, name, price, currency) VALUES (?, ?, ?, ?)",
                restaurantId,
                name,
                new BigDecimal(price),
                currency
        );
        return jdbcTemplate.queryForObject(
                "SELECT id FROM menu_items WHERE restaurant_id = ? AND name = ?",
                Long.class,
                restaurantId,
                name
        );
    }
}
