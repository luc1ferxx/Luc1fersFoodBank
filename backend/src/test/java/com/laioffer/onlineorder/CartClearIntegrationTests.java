package com.laioffer.onlineorder;


import com.laioffer.onlineorder.service.CustomerService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;


import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;


/**
 * DELETE /cart: the way out of a 409 CART_RESTAURANT_CONFLICT.
 */
@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=" +
                "org.springframework.boot.autoconfigure.session.SessionAutoConfiguration," +
                "org.springframework.boot.autoconfigure.session.RedisSessionAutoConfiguration"
})
@AutoConfigureMockMvc
@ActiveProfiles("h2")
class CartClearIntegrationTests {

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.session.store-type", () -> "none");
        registry.add("spring.cache.type", () -> "none");
        registry.add("app.kafka.enabled", () -> "false");
        registry.add("app.security.rate-limit.enabled", () -> "false");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CustomerService customerService;


    @Test
    void clearCart_shouldEmptyTheCartIdempotentlyAndLetAnotherRestaurantIn() throws Exception {
        MockHttpSession customer = signUpAndLogin();
        long burgerItem = menuItemOf("Burger King");
        long tofuItem = menuItemOf("SGD Tofu House");

        addToCart(customer, burgerItem).andExpect(status().isOk());
        addToCart(customer, burgerItem).andExpect(status().isOk());
        addToCart(customer, tofuItem)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CART_RESTAURANT_CONFLICT"));

        mockMvc.perform(delete("/cart").session(customer))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        mockMvc.perform(get("/cart").session(customer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order_items.length()").value(0))
                .andExpect(jsonPath("$.total_price").value(0.0));
        // Clearing an empty cart is the same success, so a retried request is safe.
        mockMvc.perform(delete("/cart").session(customer))
                .andExpect(status().isNoContent());

        addToCart(customer, tofuItem).andExpect(status().isOk());
        mockMvc.perform(get("/cart").session(customer))
                .andExpect(jsonPath("$.order_items.length()").value(1))
                .andExpect(jsonPath("$.order_items[0].menu_item_id").value(tofuItem));
    }


    @Test
    void clearCart_forAnAccountWithoutACartRow_shouldBeTheSameSuccess() throws Exception {
        // The demo merchants are seeded by SQL with a login but no carts row.
        String email = "merchant.burgerking@laifood.com";
        Long customerId = customerService.getCustomerByEmail(email).id();
        Assertions.assertEquals(0, cartRows(customerId));
        MockHttpSession merchant = login(email, "merchant123");

        mockMvc.perform(delete("/cart").session(merchant))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        mockMvc.perform(delete("/cart").session(merchant))
                .andExpect(status().isNoContent());

        Assertions.assertEquals(0, cartRows(customerId), "clearing creates no cart");
    }


    @Test
    void clearCart_shouldRequireALogin() throws Exception {
        mockMvc.perform(delete("/cart"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }


    private org.springframework.test.web.servlet.ResultActions addToCart(MockHttpSession session, long menuItemId)
            throws Exception {
        return mockMvc.perform(post("/cart")
                .session(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"menu_id\": " + menuItemId + "}"));
    }


    private long menuItemOf(String restaurantName) {
        return jdbcTemplate.queryForObject("""
                SELECT MIN(menu_items.id)
                FROM menu_items
                JOIN restaurants ON restaurants.id = menu_items.restaurant_id
                WHERE restaurants.name = ?
                """, Long.class, restaurantName);
    }


    private int cartRows(Long customerId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM carts WHERE customer_id = ?", Integer.class, customerId);
    }


    private MockHttpSession signUpAndLogin() throws Exception {
        String email = "cart-clear-" + System.nanoTime() + "@example.com";
        customerService.signUp(email, "demo123", "Cart", "Clear");
        return login(email, "demo123");
    }


    private MockHttpSession login(String email, String password) throws Exception {
        return (MockHttpSession) mockMvc.perform(post("/login")
                        .param("username", email)
                        .param("password", password))
                .andExpect(status().isOk())
                .andReturn()
                .getRequest()
                .getSession(false);
    }
}
