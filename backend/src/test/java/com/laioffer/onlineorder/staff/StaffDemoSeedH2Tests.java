package com.laioffer.onlineorder.staff;


import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;


import java.util.List;


import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;


/**
 * The demo seed on the h2 profile (the same migrations, V8 included, and afterMigrate callbacks as PostgreSQL): each
 * demo rider gets one rider login, the seed stays idempotent when Flyway runs it again on the next start, and the demo
 * logins work against the rider and merchant endpoints.
 */
@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=" +
                "org.springframework.boot.autoconfigure.session.SessionAutoConfiguration," +
                "org.springframework.boot.autoconfigure.session.RedisSessionAutoConfiguration"
})
@AutoConfigureMockMvc
@ActiveProfiles("h2")
class StaffDemoSeedH2Tests {

    private static final List<String> DEMO_RIDERS = List.of(
            "rider.alice@laifood.com",
            "rider.bob@laifood.com",
            "rider.carol@laifood.com"
    );

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
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Flyway flyway;


    @Test
    void demoSeed_shouldBindEachDemoRiderToOneLoginAndStayIdempotent() throws Exception {
        // The next start runs the afterMigrate callbacks again.
        flyway.migrate();

        for (String email : DEMO_RIDERS) {
            Assertions.assertEquals(1, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM customers WHERE email = ?", Integer.class, email), email);
            Assertions.assertEquals(List.of("ROLE_RIDER"), jdbcTemplate.queryForList(
                    "SELECT authority FROM authorities WHERE email = ?", String.class, email), email);
            Assertions.assertEquals(1, jdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                    FROM riders
                    JOIN customers ON customers.id = riders.customer_id
                    WHERE customers.email = ?
                      AND riders.active = TRUE
                      AND riders.on_duty = TRUE
                    """, Integer.class, email), email);
        }
        Assertions.assertEquals(3, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM riders WHERE name LIKE 'Demo Rider %'", Integer.class));
        Assertions.assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM merchant_accounts WHERE active = FALSE", Integer.class));

        MockHttpSession bob = login("rider.bob@laifood.com", "rider123");
        mockMvc.perform(get("/rider/me").session(bob))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Demo Rider Bob"))
                .andExpect(jsonPath("$.on_duty").value(true));
        mockMvc.perform(get("/me").session(bob))
                .andExpect(jsonPath("$.roles[0]").value("ROLE_RIDER"))
                .andExpect(jsonPath("$.rider.name").value("Demo Rider Bob"));

        MockHttpSession merchant = login("merchant.fashionwok@laifood.com", "merchant123");
        mockMvc.perform(get("/me").session(merchant))
                .andExpect(jsonPath("$.merchant.restaurant_name").value("Fashion Wok"))
                .andExpect(jsonPath("$.merchant.active").value(true));
        mockMvc.perform(get("/merchant/orders").session(merchant)).andExpect(status().isOk());
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
