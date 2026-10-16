package com.laioffer.onlineorder.staff;


import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;


import java.util.List;


import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;


/**
 * The demo seed on the h2 profile (the same migrations, V8 included, and afterMigrate callbacks as PostgreSQL): each
 * demo rider gets one rider login, the seed stays idempotent when Flyway runs it again on the next start, the demo
 * logins work against the rider and merchant endpoints, and the seed never grants a staff role to an account it did
 * not create.
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

    @Autowired
    private PasswordEncoder passwordEncoder;


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


    @Test
    void demoSeed_onAnExistingDatabase_shouldNotMakeASelfRegisteredAccountARiderOrMerchant() throws Exception {
        // An existing database where someone signed up with a demo staff email before the seed bound it: the account
        // has a BCrypt password and ROLE_USER, Demo Rider Alice has no login and the email no merchant account.
        String rider = "rider.alice@laifood.com";
        String merchant = "merchant.burgerking@laifood.com";
        Long riderLoginId = customerId(rider);
        Long merchantLoginId = customerId(merchant);
        Long burgerKingId = jdbcTemplate.queryForObject(
                "SELECT restaurant_id FROM merchant_accounts WHERE customer_id = ?", Long.class, merchantLoginId);
        String bcrypt = passwordEncoder.encode("chosen-by-the-registrant");
        try {
            for (String email : List.of(rider, merchant)) {
                jdbcTemplate.update("DELETE FROM authorities WHERE email = ?", email);
                jdbcTemplate.update("INSERT INTO authorities (email, authority) VALUES (?, 'ROLE_USER')", email);
                jdbcTemplate.update("UPDATE customers SET password = ? WHERE email = ?", bcrypt, email);
            }
            jdbcTemplate.update("UPDATE riders SET customer_id = NULL WHERE customer_id = ?", riderLoginId);
            jdbcTemplate.update("DELETE FROM merchant_accounts WHERE customer_id = ?", merchantLoginId);

            flyway.migrate();

            for (String email : List.of(rider, merchant)) {
                Assertions.assertEquals(List.of("ROLE_USER"), jdbcTemplate.queryForList(
                        "SELECT authority FROM authorities WHERE email = ?", String.class, email), email);
            }
            Assertions.assertNull(jdbcTemplate.queryForObject(
                    "SELECT customer_id FROM riders WHERE name = 'Demo Rider Alice'", Long.class));
            Assertions.assertEquals(0, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM merchant_accounts WHERE customer_id = ?", Integer.class, merchantLoginId));
            MockHttpSession registrant = login(rider, "chosen-by-the-registrant");
            mockMvc.perform(get("/rider/me").session(registrant)).andExpect(status().isForbidden());
            mockMvc.perform(get("/me").session(registrant))
                    .andExpect(jsonPath("$.roles[0]").value("ROLE_USER"))
                    .andExpect(jsonPath("$.rider").value(nullValue()));
        } finally {
            // Back to the seeded logins: the seed binds and grants them again on the next run.
            for (String email : List.of(rider, merchant)) {
                jdbcTemplate.update("DELETE FROM authorities WHERE email = ? AND authority = 'ROLE_USER'", email);
            }
            jdbcTemplate.update("UPDATE customers SET password = '{noop}rider123' WHERE email = ?", rider);
            jdbcTemplate.update("UPDATE customers SET password = '{noop}merchant123' WHERE email = ?", merchant);
            flyway.migrate();
        }
        Assertions.assertEquals(riderLoginId, jdbcTemplate.queryForObject(
                "SELECT customer_id FROM riders WHERE name = 'Demo Rider Alice'", Long.class));
        Assertions.assertEquals(List.of("ROLE_RIDER"), jdbcTemplate.queryForList(
                "SELECT authority FROM authorities WHERE email = ?", String.class, rider));
        Assertions.assertEquals(List.of("ROLE_MERCHANT"), jdbcTemplate.queryForList(
                "SELECT authority FROM authorities WHERE email = ?", String.class, merchant));
        Assertions.assertEquals(burgerKingId, jdbcTemplate.queryForObject(
                "SELECT restaurant_id FROM merchant_accounts WHERE customer_id = ?", Long.class, merchantLoginId));
    }


    private Long customerId(String email) {
        return jdbcTemplate.queryForObject("SELECT id FROM customers WHERE email = ?", Long.class, email);
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
