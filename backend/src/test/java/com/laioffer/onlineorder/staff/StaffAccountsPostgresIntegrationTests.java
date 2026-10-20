package com.laioffer.onlineorder.staff;


import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.laioffer.onlineorder.PostgresRowLocks;
import com.laioffer.onlineorder.fulfilment.dispatch.DispatchService;
import com.laioffer.onlineorder.fulfilment.dispatch.RiderService;
import com.laioffer.onlineorder.fulfilment.order.OrderActor;
import com.laioffer.onlineorder.fulfilment.order.OrderLifecycleCommands;
import com.laioffer.onlineorder.messaging.OrderEventEnvelope;
import com.laioffer.onlineorder.model.OrderDto;
import com.laioffer.onlineorder.model.OrderStatus;
import com.laioffer.onlineorder.service.CartService;
import com.laioffer.onlineorder.service.CustomerService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;


import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;


import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;


/**
 * Merchant and rider logins on real PostgreSQL: admin provisioning, GET /me, the rider API, deactivation taking
 * effect on the next request of a session that still carries the role, the role matrix, and that a rider only ever
 * reaches their own deliveries. Kafka is off; dispatch is driven by feeding it the order.accepted the outbox recorded.
 */
@SpringBootTest(properties = {
        // Sessions live in the MockMvc session instead of Redis (it still caches the authorities like a real one).
        "spring.autoconfigure.exclude=" +
                "org.springframework.boot.session.autoconfigure.SessionAutoConfiguration," +
                "org.springframework.boot.session.data.redis.autoconfigure.SessionDataRedisAutoConfiguration"
})
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class StaffAccountsPostgresIntegrationTests {

    private static final String ADMIN = "demo@laifood.com";
    private static final String DEMO_PASSWORD = "demo123";
    private static final String BURGER_KING_MERCHANT = "merchant.burgerking@laifood.com";
    private static final String DEMO_RIDER = "rider.alice@laifood.com";

    @Container
    static PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("onlineorder")
            .withUsername("postgres")
            .withPassword("secret");


    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.locations", () -> "classpath:db/migration,classpath:db/demo");
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
    private ObjectMapper objectMapper;

    @Autowired
    private CustomerService customerService;

    @Autowired
    private CartService cartService;

    @Autowired
    private OrderLifecycleCommands orderLifecycleCommands;

    @Autowired
    private DispatchService dispatchService;

    @Autowired
    private RiderService riderService;

    @Autowired
    private PlatformTransactionManager transactionManager;


    @Test
    void admin_shouldProvisionAMerchantWhoCanSignInAndWorkTheirInbox() throws Exception {
        MockHttpSession admin = login(ADMIN, DEMO_PASSWORD);
        Long burgerKingId = restaurantId("Burger King");
        String email = "Owner-" + System.nanoTime() + "@Example.com";

        JsonNode merchant = json(mockMvc.perform(post("/admin/merchants")
                        .session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(merchantBody(email, "s3cret-pass", burgerKingId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value(email.toLowerCase()))
                .andExpect(jsonPath("$.first_name").value("Olive"))
                .andExpect(jsonPath("$.last_name").value("Owner"))
                .andExpect(jsonPath("$.restaurant_id").value(burgerKingId))
                .andExpect(jsonPath("$.restaurant_name").value("Burger King"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.created_at").exists()));
        long merchantId = merchant.get("merchant_id").asLong();
        long customerId = merchant.get("customer_id").asLong();

        // A BCrypt password through the shared PasswordEncoder, only the merchant role, and no customer cart.
        Assertions.assertTrue(jdbcTemplate.queryForObject(
                "SELECT password FROM customers WHERE id = ?", String.class, customerId).startsWith("{bcrypt}"));
        Assertions.assertEquals(List.of("ROLE_MERCHANT"), jdbcTemplate.queryForList(
                "SELECT authority FROM authorities WHERE email = ?", String.class, email.toLowerCase()));
        Assertions.assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM carts WHERE customer_id = ?", Integer.class, customerId));

        MockHttpSession owner = login(email.toUpperCase(), "s3cret-pass");
        mockMvc.perform(get("/me").session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(customerId))
                .andExpect(jsonPath("$.roles[0]").value("ROLE_MERCHANT"))
                .andExpect(jsonPath("$.roles.length()").value(1))
                .andExpect(jsonPath("$.merchant.merchant_id").value(merchantId))
                .andExpect(jsonPath("$.merchant.restaurant_id").value(burgerKingId))
                .andExpect(jsonPath("$.merchant.restaurant_name").value("Burger King"))
                .andExpect(jsonPath("$.merchant.active").value(true))
                .andExpect(jsonPath("$", hasKey("rider")))
                .andExpect(jsonPath("$.rider").value(nullValue()));
        mockMvc.perform(get("/merchant/orders").session(owner)).andExpect(status().isOk());

        mockMvc.perform(get("/admin/merchants").session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.merchant_id == %d)].email".formatted(merchantId)).value(email.toLowerCase()))
                .andExpect(jsonPath("$[?(@.email == '%s')].restaurant_name".formatted(BURGER_KING_MERCHANT))
                        .value("Burger King"));
    }


    @Test
    void provisioning_shouldRejectTakenEmailsUnknownRestaurantsAndInvalidBodies() throws Exception {
        MockHttpSession admin = login(ADMIN, DEMO_PASSWORD);
        Long burgerKingId = restaurantId("Burger King");

        mockMvc.perform(post("/admin/merchants")
                        .session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(merchantBody(BURGER_KING_MERCHANT.toUpperCase(), "pass", burgerKingId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_REGISTERED"));
        mockMvc.perform(post("/admin/riders")
                        .session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(riderBody(ADMIN, "pass")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_REGISTERED"));

        String orphan = "orphan-" + System.nanoTime() + "@example.com";
        mockMvc.perform(post("/admin/merchants")
                        .session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(merchantBody(orphan, "pass", 987654L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        Assertions.assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM customers WHERE email = ?", Integer.class, orphan), "no login without a binding");

        for (String invalid : List.of(
                "{\"email\": \"x@example.com\", \"password\": \"\", \"restaurant_id\": %d}".formatted(burgerKingId),
                "{\"email\": \"not-an-email\", \"password\": \"pass\", \"restaurant_id\": %d}".formatted(burgerKingId),
                "{\"email\": \"x@example.com\", \"password\": \"pass\"}"
        )) {
            mockMvc.perform(post("/admin/merchants").session(admin).contentType(MediaType.APPLICATION_JSON).content(invalid))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        mockMvc.perform(post("/admin/riders")
                        .session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"x@example.com\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(patch("/admin/merchants/{id}", 987654).session(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\": false}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(patch("/admin/riders/{id}", 987654).session(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\": false}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(patch("/admin/riders/{id}", riderIdOf(DEMO_RIDER)).session(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }


    @Test
    void deactivatedMerchant_shouldBeForbiddenOnTheirNextRequestUntilReactivated() throws Exception {
        MockHttpSession admin = login(ADMIN, DEMO_PASSWORD);
        String email = "shift-" + System.nanoTime() + "@example.com";
        long merchantId = json(mockMvc.perform(post("/admin/merchants")
                        .session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(merchantBody(email, "pass-1", restaurantId("SGD Tofu House"))))
                .andExpect(status().isCreated())).get("merchant_id").asLong();
        MockHttpSession merchant = login(email, "pass-1");
        mockMvc.perform(get("/merchant/orders").session(merchant)).andExpect(status().isOk());

        mockMvc.perform(patch("/admin/merchants/{id}", merchantId).session(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\": false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.merchant_id").value(merchantId))
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.restaurant_name").value("SGD Tofu House"))
                .andExpect(jsonPath("$.active").value(false));

        // The session still carries ROLE_MERCHANT; the module re-checks the account on every request.
        mockMvc.perform(get("/merchant/orders").session(merchant))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(post("/merchant/orders/{id}/accept", 1).session(merchant))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/me").session(merchant))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[0]").value("ROLE_MERCHANT"))
                .andExpect(jsonPath("$.merchant.active").value(false));

        mockMvc.perform(patch("/admin/merchants/{id}", merchantId).session(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));
        mockMvc.perform(get("/merchant/orders").session(merchant)).andExpect(status().isOk());
    }


    @Test
    void admin_shouldProvisionARiderWhoManagesTheirOwnAvailability() throws Exception {
        MockHttpSession admin = login(ADMIN, DEMO_PASSWORD);
        String email = "dana-" + System.nanoTime() + "@example.com";
        JsonNode created = json(mockMvc.perform(post("/admin/riders")
                        .session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(riderBody(email, "ride-on")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.first_name").value("Dana"))
                .andExpect(jsonPath("$.last_name").value("Rider"))
                .andExpect(jsonPath("$.name").value("Dana Rider"))
                .andExpect(jsonPath("$.phone").value("(408) 555-0199"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.on_duty").value(false))
                .andExpect(jsonPath("$.active_deliveries").value(0))
                .andExpect(jsonPath("$.created_at").exists()));
        long riderId = created.get("rider_id").asLong();
        Assertions.assertEquals(List.of("ROLE_RIDER"), jdbcTemplate.queryForList(
                "SELECT authority FROM authorities WHERE email = ?", String.class, email));

        MockHttpSession rider = login(email, "ride-on");
        mockMvc.perform(get("/me").session(rider))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[0]").value("ROLE_RIDER"))
                .andExpect(jsonPath("$.rider.rider_id").value(riderId))
                .andExpect(jsonPath("$.rider.name").value("Dana Rider"))
                .andExpect(jsonPath("$.rider.on_duty").value(false))
                .andExpect(jsonPath("$.rider.active").value(true))
                .andExpect(jsonPath("$", hasKey("merchant")))
                .andExpect(jsonPath("$.merchant").value(nullValue()));
        mockMvc.perform(get("/rider/me").session(rider))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rider_id").value(riderId))
                .andExpect(jsonPath("$.name").value("Dana Rider"))
                .andExpect(jsonPath("$.phone").value("(408) 555-0199"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.on_duty").value(false))
                .andExpect(jsonPath("$.active_deliveries").value(0));
        mockMvc.perform(put("/rider/me/availability").session(rider)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"on_duty\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.on_duty").value(true));
        mockMvc.perform(put("/rider/me/availability").session(rider)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(get("/rider/deliveries").session(rider))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(get("/admin/riders").session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.rider_id == %d)].on_duty".formatted(riderId)).value(true))
                .andExpect(jsonPath("$[?(@.email == '%s')].name".formatted(DEMO_RIDER)).value("Demo Rider Alice"));

        // Deactivation takes the rider off duty and locks them out of the rider API on their next request.
        mockMvc.perform(patch("/admin/riders/{id}", riderId).session(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\": false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.on_duty").value(false))
                .andExpect(jsonPath("$.email").value(email));
        for (MockHttpServletRequestBuilder request : List.of(
                get("/rider/me"),
                get("/rider/deliveries"),
                put("/rider/me/availability").contentType(MediaType.APPLICATION_JSON).content("{\"on_duty\": true}"),
                post("/rider/deliveries/{id}/picked-up", 1)
        )) {
            mockMvc.perform(request.session(rider))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
        mockMvc.perform(get("/me").session(rider))
                .andExpect(jsonPath("$.rider.active").value(false))
                .andExpect(jsonPath("$.rider.on_duty").value(false));
        Assertions.assertFalse(jdbcTemplate.queryForObject(
                "SELECT on_duty FROM riders WHERE id = ?", Boolean.class, riderId));
    }


    @Test
    void dispatch_shouldOnlyAssignActiveOnDutyRiders_andARiderShouldOnlyReachTheirOwnDeliveries() throws Exception {
        MockHttpSession admin = login(ADMIN, DEMO_PASSWORD);
        String emailA = "rider-a-" + System.nanoTime() + "@example.com";
        String emailB = "rider-b-" + System.nanoTime() + "@example.com";
        long riderA = provisionRider(admin, emailA);
        long riderB = provisionRider(admin, emailB);
        MockHttpSession sessionA = login(emailA, "ride-on");
        MockHttpSession sessionB = login(emailB, "ride-on");
        Map<Long, Boolean> otherRidersOnDuty = takeOtherRidersOffDuty(List.of(riderA, riderB));
        try {
            // Nobody on duty: dispatch fails (retried, then dead-lettered by the listener) and assigns nothing.
            OrderEventEnvelope first = acceptedOrderAt("Burger King");
            Assertions.assertThrows(IllegalStateException.class, () -> dispatchService.handle(first));
            Assertions.assertEquals(0, deliveryCount(first.aggregateId()));

            // An inactive rider is never assigned, even with the on-duty flag set.
            mockMvc.perform(patch("/admin/riders/{id}", riderB).session(admin)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"active\": false}"))
                    .andExpect(status().isOk());
            jdbcTemplate.update("UPDATE riders SET on_duty = TRUE WHERE id = ?", riderB);
            Assertions.assertThrows(IllegalStateException.class, () -> dispatchService.handle(first));
            jdbcTemplate.update("UPDATE riders SET on_duty = FALSE WHERE id = ?", riderB);

            mockMvc.perform(put("/rider/me/availability").session(sessionA)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"on_duty\": true}"))
                    .andExpect(status().isOk());
            dispatchService.handle(first);
            Long deliveryId = jdbcTemplate.queryForObject(
                    "SELECT id FROM deliveries WHERE order_id = ? AND rider_id = ?", Long.class, first.aggregateId(), riderA);

            mockMvc.perform(get("/rider/deliveries").session(sessionA))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].delivery_id").value(deliveryId))
                    .andExpect(jsonPath("$[0].order_id").value(first.aggregateId()))
                    .andExpect(jsonPath("$[0].restaurant_id").value(restaurantId("Burger King")))
                    .andExpect(jsonPath("$[0].restaurant_name").value("Burger King"))
                    .andExpect(jsonPath("$[0].status").value("ASSIGNED"))
                    .andExpect(jsonPath("$[0].assigned_at").exists())
                    .andExpect(jsonPath("$[0]", hasKey("picked_up_at")))
                    .andExpect(jsonPath("$[0].picked_up_at").value(nullValue()))
                    .andExpect(jsonPath("$[0].delivered_at").value(nullValue()));
            mockMvc.perform(get("/rider/me").session(sessionA))
                    .andExpect(jsonPath("$.active_deliveries").value(1));

            // Rider B (reactivated) cannot see or act on rider A's delivery: 404, and nothing changes.
            mockMvc.perform(patch("/admin/riders/{id}", riderB).session(admin)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"active\": true}"))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/rider/deliveries").param("status", "ASSIGNED,PICKED_UP,DELIVERED,CANCELLED")
                            .session(sessionB))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(0));
            for (String action : List.of("picked-up", "delivered")) {
                mockMvc.perform(post("/rider/deliveries/{id}/" + action, deliveryId).session(sessionB))
                        .andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
            }
            mockMvc.perform(post("/rider/deliveries/{id}/picked-up", 987654).session(sessionA))
                    .andExpect(status().isNotFound());
            Assertions.assertEquals("ASSIGNED", deliveryStatus(deliveryId));
            Assertions.assertEquals(List.of("delivery.assigned"), deliveryEventTypes(deliveryId));

            // Rider A moves it through the same state machine as the admin simulation, idempotently.
            mockMvc.perform(post("/rider/deliveries/{id}/delivered", deliveryId).session(sessionA))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("DELIVERY_STATUS_CONFLICT"));
            for (int attempt = 0; attempt < 2; attempt++) {
                mockMvc.perform(post("/rider/deliveries/{id}/picked-up", deliveryId).session(sessionA))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.delivery_id").value(deliveryId))
                        .andExpect(jsonPath("$.status").value("PICKED_UP"))
                        .andExpect(jsonPath("$.restaurant_name").value("Burger King"))
                        .andExpect(jsonPath("$.picked_up_at").exists());
            }
            mockMvc.perform(get("/rider/deliveries").param("status", "DELIVERED").session(sessionA))
                    .andExpect(jsonPath("$.length()").value(0));
            for (int attempt = 0; attempt < 2; attempt++) {
                mockMvc.perform(post("/rider/deliveries/{id}/delivered", deliveryId).session(sessionA))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.status").value("DELIVERED"))
                        .andExpect(jsonPath("$.delivered_at").exists());
            }
            Assertions.assertEquals(
                    List.of("delivery.assigned", "delivery.picked_up", "delivery.delivered"),
                    deliveryEventTypes(deliveryId)
            );
            Assertions.assertEquals(0, jdbcTemplate.queryForObject(
                    "SELECT active_deliveries FROM riders WHERE id = ?", Integer.class, riderA));
            mockMvc.perform(get("/rider/deliveries").session(sessionA))
                    .andExpect(jsonPath("$.length()").value(0));
            mockMvc.perform(get("/rider/deliveries").param("status", "delivered").session(sessionA))
                    .andExpect(jsonPath("$[0].delivery_id").value(deliveryId));
            mockMvc.perform(get("/rider/deliveries").param("status", "LOST").session(sessionA))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("BAD_REQUEST"));

            // Off duty: no new assignments, even for the least-loaded rider.
            mockMvc.perform(put("/rider/me/availability").session(sessionA)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"on_duty\": false}"))
                    .andExpect(jsonPath("$.on_duty").value(false));
            OrderEventEnvelope second = acceptedOrderAt("Fashion Wok");
            Assertions.assertThrows(IllegalStateException.class, () -> dispatchService.handle(second));
        } finally {
            otherRidersOnDuty.forEach((riderId, onDuty) ->
                    jdbcTemplate.update("UPDATE riders SET on_duty = ? WHERE id = ?", onDuty, riderId));
        }
    }


    @Test
    void noRiderOnDuty_shouldRecoverOnceAnAdminPutsARiderOnDuty() throws Exception {
        MockHttpSession admin = login(ADMIN, DEMO_PASSWORD);
        String email = "standby-" + System.nanoTime() + "@example.com";
        Map<Long, Boolean> otherRidersOnDuty = takeOtherRidersOffDuty(List.of());
        try {
            OrderEventEnvelope accepted = acceptedOrderAt("SGD Tofu House");
            Assertions.assertThrows(IllegalStateException.class, () -> dispatchService.handle(accepted));

            // Provisioning alone does not help: a new rider starts off duty, so a replay would fail again.
            long riderId = provisionRider(admin, email);
            Assertions.assertThrows(IllegalStateException.class, () -> dispatchService.handle(accepted));

            // Deactivated riders cannot be put on duty, neither alone nor in the same change.
            mockMvc.perform(patch("/admin/riders/{id}", riderId).session(admin)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"active\": false}"))
                    .andExpect(status().isOk());
            for (String body : List.of("{\"on_duty\": true}", "{\"active\": false, \"on_duty\": true}")) {
                mockMvc.perform(patch("/admin/riders/{id}", riderId).session(admin)
                                .contentType(MediaType.APPLICATION_JSON).content(body))
                        .andExpect(status().isConflict())
                        .andExpect(jsonPath("$.code").value("RIDER_INACTIVE"));
            }
            // Reactivating alone leaves the rider off duty.
            mockMvc.perform(patch("/admin/riders/{id}", riderId).session(admin)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"active\": true}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.active").value(true))
                    .andExpect(jsonPath("$.on_duty").value(false));
            Assertions.assertThrows(IllegalStateException.class, () -> dispatchService.handle(accepted));

            // The operator's lever: the admin puts the rider on duty, then the replay assigns them.
            mockMvc.perform(patch("/admin/riders/{id}", riderId).session(admin)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"on_duty\": true}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.rider_id").value(riderId))
                    .andExpect(jsonPath("$.active").value(true))
                    .andExpect(jsonPath("$.on_duty").value(true))
                    .andExpect(jsonPath("$.email").value(email));
            dispatchService.handle(accepted);
            Assertions.assertEquals(riderId, jdbcTemplate.queryForObject(
                    "SELECT rider_id FROM deliveries WHERE order_id = ?", Long.class, accepted.aggregateId()));

            // The rider sees it on their own login; an admin can also take them off duty again.
            mockMvc.perform(get("/rider/deliveries").session(login(email, "ride-on")))
                    .andExpect(jsonPath("$[0].order_id").value(accepted.aggregateId()));
            mockMvc.perform(patch("/admin/riders/{id}", riderId).session(admin)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"on_duty\": false}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.on_duty").value(false))
                    .andExpect(jsonPath("$.active_deliveries").value(1));
        } finally {
            otherRidersOnDuty.forEach((riderId, onDuty) ->
                    jdbcTemplate.update("UPDATE riders SET on_duty = ? WHERE id = ?", onDuty, riderId));
        }
    }


    @Test
    void deactivatedRidersOpenDelivery_shouldBeReassignedToAnOnDutyRiderWithoutCompletingTheOrder() throws Exception {
        MockHttpSession admin = login(ADMIN, DEMO_PASSWORD);
        String emailA = "leaving-" + System.nanoTime() + "@example.com";
        String emailB = "covering-" + System.nanoTime() + "@example.com";
        long riderA = provisionRider(admin, emailA);
        long riderB = provisionRider(admin, emailB);
        MockHttpSession sessionA = login(emailA, "ride-on");
        MockHttpSession sessionB = login(emailB, "ride-on");
        Map<Long, Boolean> otherRidersOnDuty = takeOtherRidersOffDuty(List.of(riderA, riderB));
        try {
            mockMvc.perform(put("/rider/me/availability").session(sessionA)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"on_duty\": true}"))
                    .andExpect(status().isOk());
            OrderEventEnvelope accepted = acceptedOrderAt("Burger King");
            dispatchService.handle(accepted);
            Long deliveryId = jdbcTemplate.queryForObject(
                    "SELECT id FROM deliveries WHERE order_id = ? AND rider_id = ?", Long.class,
                    accepted.aggregateId(), riderA);
            mockMvc.perform(post("/rider/deliveries/{id}/picked-up", deliveryId).session(sessionA))
                    .andExpect(status().isOk());

            // Deactivated mid-delivery: the response shows the open delivery, which rider A can no longer move.
            mockMvc.perform(patch("/admin/riders/{id}", riderA).session(admin)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"active\": false}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.active_deliveries").value(1));
            mockMvc.perform(post("/rider/deliveries/{id}/delivered", deliveryId).session(sessionA))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get("/admin/deliveries").param("rider_id", String.valueOf(riderA)).session(admin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].id").value(deliveryId))
                    .andExpect(jsonPath("$[0].status").value("PICKED_UP"));

            // Only an active, on-duty rider can take it over.
            mockMvc.perform(post("/admin/deliveries/{id}/reassign", deliveryId).session(admin)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"rider_id\": %d}".formatted(riderB)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("RIDER_NOT_DISPATCHABLE"));
            mockMvc.perform(post("/admin/deliveries/{id}/reassign", deliveryId).session(admin)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"rider_id\": 987654}"))
                    .andExpect(status().isNotFound());
            mockMvc.perform(post("/admin/deliveries/{id}/reassign", deliveryId).session(admin)
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
            mockMvc.perform(put("/rider/me/availability").session(sessionB)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"on_duty\": true}"))
                    .andExpect(status().isOk());
            for (int attempt = 0; attempt < 2; attempt++) {
                mockMvc.perform(post("/admin/deliveries/{id}/reassign", deliveryId).session(admin)
                                .contentType(MediaType.APPLICATION_JSON).content("{\"rider_id\": %d}".formatted(riderB)))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.id").value(deliveryId))
                        .andExpect(jsonPath("$.rider_id").value(riderB))
                        .andExpect(jsonPath("$.rider_name").value("Dana Rider"))
                        .andExpect(jsonPath("$.status").value("PICKED_UP"));
            }
            Assertions.assertEquals(0, riderLoad(riderA));
            Assertions.assertEquals(1, riderLoad(riderB));
            Assertions.assertEquals(
                    List.of("delivery.assigned", "delivery.picked_up", "delivery.reassigned"),
                    deliveryEventTypes(deliveryId),
                    "one reassignment event, also when the admin retried"
            );
            Assertions.assertEquals("ACCEPTED", orderStatus(accepted.aggregateId()), "nothing completed the order");

            // Rider B finishes it; rider A, even reactivated, no longer reaches it.
            mockMvc.perform(get("/rider/deliveries").session(sessionB))
                    .andExpect(jsonPath("$[0].delivery_id").value(deliveryId))
                    .andExpect(jsonPath("$[0].status").value("PICKED_UP"));
            mockMvc.perform(post("/rider/deliveries/{id}/delivered", deliveryId).session(sessionB))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("DELIVERED"));
            mockMvc.perform(patch("/admin/riders/{id}", riderA).session(admin)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"active\": true}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.active_deliveries").value(0));
            mockMvc.perform(post("/rider/deliveries/{id}/delivered", deliveryId).session(sessionA))
                    .andExpect(status().isNotFound());
            Assertions.assertEquals(0, riderLoad(riderB));

            // A finished delivery cannot be reassigned.
            mockMvc.perform(post("/admin/deliveries/{id}/reassign", deliveryId).session(admin)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"rider_id\": %d}".formatted(riderA)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("DELIVERY_STATUS_CONFLICT"));
        } finally {
            otherRidersOnDuty.forEach((riderId, onDuty) ->
                    jdbcTemplate.update("UPDATE riders SET on_duty = ? WHERE id = ?", onDuty, riderId));
        }
    }


    @Test
    void riderGoingOnDuty_whileAnAdminDeactivatesThem_shouldNotLeaveAnInactiveRiderOnDuty() throws Exception {
        MockHttpSession admin = login(ADMIN, DEMO_PASSWORD);
        String email = "racing-" + System.nanoTime() + "@example.com";
        long riderId = provisionRider(admin, email);
        Long customerId = jdbcTemplate.queryForObject("SELECT customer_id FROM riders WHERE id = ?", Long.class, riderId);
        CountDownLatch deactivated = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            // The admin's deactivation holds the rider row, uncommitted ...
            Future<?> deactivation = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(
                    status -> {
                        jdbcTemplate.update(
                                "UPDATE riders SET active = FALSE, on_duty = FALSE WHERE id = ?", riderId);
                        deactivated.countDown();
                        try {
                            Assertions.assertTrue(commit.await(30, TimeUnit.SECONDS));
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(ex);
                        }
                    }));
            Assertions.assertTrue(deactivated.await(30, TimeUnit.SECONDS));

            // ... while the rider's own request still reads them as active and then waits for the row.
            Future<?> goOnDuty = executor.submit(() -> riderService.setOnDuty(customerId, true));
            PostgresRowLocks.awaitLockWaiters(
                    jdbcTemplate, 1, "The rider's update never waited for the deactivation's row lock");
            commit.countDown();
            deactivation.get(30, TimeUnit.SECONDS);

            ExecutionException failure = Assertions.assertThrows(
                    ExecutionException.class, () -> goOnDuty.get(30, TimeUnit.SECONDS));
            Assertions.assertInstanceOf(AccessDeniedException.class, failure.getCause());
            Assertions.assertEquals(Map.of("active", false, "on_duty", false), jdbcTemplate.queryForMap(
                    "SELECT active, on_duty FROM riders WHERE id = ?", riderId));
        } finally {
            commit.countDown();
            executor.shutdownNow();
        }
    }


    @Test
    void roleMatrix_shouldLetEachRoleReachOnlyItsOwnEndpoints() throws Exception {
        MockHttpSession admin = login(ADMIN, DEMO_PASSWORD);
        MockHttpSession merchant = login(BURGER_KING_MERCHANT, "merchant123");
        MockHttpSession rider = login(DEMO_RIDER, "rider123");
        MockHttpSession customer = signUpAndLogin();
        long demoRiderId = riderIdOf(DEMO_RIDER);
        long demoMerchantId = jdbcTemplate.queryForObject("""
                SELECT merchant_accounts.id
                FROM merchant_accounts
                JOIN customers ON customers.id = merchant_accounts.customer_id
                WHERE customers.email = ?
                """, Long.class, BURGER_KING_MERCHANT);
        Map<String, MockHttpSession> sessions = new LinkedHashMap<>();
        sessions.put("anonymous", null);
        sessions.put("customer", customer);
        sessions.put("merchant", merchant);
        sessions.put("rider", rider);
        sessions.put("admin", admin);

        // Expected status per caller, in the order of the sessions above. Every request is valid for its owner, and
        // none changes state: the availability PUT keeps the demo rider on duty, the PATCHes keep accounts active.
        List<Object[]> matrix = List.of(
                row(HttpMethod.GET, "/me", null, 401, 200, 200, 200, 200),
                row(HttpMethod.GET, "/rider/me", null, 401, 403, 403, 200, 403),
                row(HttpMethod.GET, "/rider/deliveries", null, 401, 403, 403, 200, 403),
                row(HttpMethod.PUT, "/rider/me/availability", "{\"on_duty\": true}", 401, 403, 403, 200, 403),
                row(HttpMethod.POST, "/rider/deliveries/987654/picked-up", null, 401, 403, 403, 404, 403),
                row(HttpMethod.GET, "/admin/merchants", null, 401, 403, 403, 403, 200),
                row(HttpMethod.GET, "/admin/riders", null, 401, 403, 403, 403, 200),
                row(HttpMethod.POST, "/admin/merchants", "{}", 401, 403, 403, 403, 400),
                row(HttpMethod.POST, "/admin/riders", "{}", 401, 403, 403, 403, 400),
                row(HttpMethod.PATCH, "/admin/merchants/" + demoMerchantId, "{\"active\": true}", 401, 403, 403, 403, 200),
                row(HttpMethod.PATCH, "/admin/riders/" + demoRiderId, "{\"active\": true}", 401, 403, 403, 403, 200),
                row(HttpMethod.PATCH, "/admin/riders/" + demoRiderId, "{\"on_duty\": true}", 401, 403, 403, 403, 200),
                row(HttpMethod.GET, "/merchant/orders", null, 401, 403, 200, 403, 403),
                row(HttpMethod.GET, "/admin/deliveries", null, 401, 403, 403, 403, 200),
                row(HttpMethod.GET, "/admin/deliveries?rider_id=" + demoRiderId, null, 401, 403, 403, 403, 200),
                row(HttpMethod.POST, "/admin/deliveries/987654/reassign", "{\"rider_id\": " + demoRiderId + "}",
                        401, 403, 403, 403, 404)
        );
        List<String> mismatches = new ArrayList<>();
        for (Object[] entry : matrix) {
            int column = 0;
            for (Map.Entry<String, MockHttpSession> caller : sessions.entrySet()) {
                int expected = (int) entry[3 + column++];
                MockHttpServletRequestBuilder request = MockMvcRequestBuilders
                        .request((HttpMethod) entry[0], (String) entry[1]);
                if (entry[2] != null) {
                    request.contentType(MediaType.APPLICATION_JSON).content((String) entry[2]);
                }
                if (caller.getValue() != null) {
                    request.session(caller.getValue());
                }
                int actual = mockMvc.perform(request).andReturn().getResponse().getStatus();
                if (actual != expected) {
                    mismatches.add(entry[0] + " " + entry[1] + " as " + caller.getKey() + ": " + actual + " != " + expected);
                }
            }
        }
        Assertions.assertTrue(mismatches.isEmpty(), String.join("\n", mismatches));

        mockMvc.perform(get("/me").session(admin))
                .andExpect(jsonPath("$.roles[0]").value("ROLE_ADMIN"))
                .andExpect(jsonPath("$.roles[1]").value("ROLE_USER"))
                .andExpect(jsonPath("$.merchant").value(nullValue()))
                .andExpect(jsonPath("$.rider").value(nullValue()));
        mockMvc.perform(get("/me").session(rider))
                .andExpect(jsonPath("$.rider.name").value("Demo Rider Alice"))
                .andExpect(jsonPath("$.rider.on_duty").value(true));
        mockMvc.perform(get("/me").session(merchant))
                .andExpect(jsonPath("$.merchant.restaurant_name").value("Burger King"));
        mockMvc.perform(get("/me").session(customer))
                .andExpect(jsonPath("$.roles[0]").value("ROLE_USER"))
                .andExpect(jsonPath("$.email", startsWith("matrix-")));
    }


    private static Object[] row(HttpMethod method, String path, String body, int... expected) {
        Object[] row = new Object[3 + expected.length];
        row[0] = method;
        row[1] = path;
        row[2] = body;
        for (int i = 0; i < expected.length; i++) {
            row[3 + i] = expected[i];
        }
        return row;
    }


    private long provisionRider(MockHttpSession admin, String email) throws Exception {
        return json(mockMvc.perform(post("/admin/riders")
                        .session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(riderBody(email, "ride-on")))
                .andExpect(status().isCreated())).get("rider_id").asLong();
    }


    /**
     * Takes every rider but {@code keep} off duty and returns their previous on-duty flags for restoring.
     */
    private Map<Long, Boolean> takeOtherRidersOffDuty(List<Long> keep) {
        Map<Long, Boolean> previous = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList("SELECT id, on_duty FROM riders")) {
            Long riderId = ((Number) row.get("id")).longValue();
            if (!keep.contains(riderId)) {
                previous.put(riderId, (Boolean) row.get("on_duty"));
            }
        }
        previous.keySet().forEach(riderId -> jdbcTemplate.update("UPDATE riders SET on_duty = FALSE WHERE id = ?", riderId));
        return previous;
    }


    /**
     * A paid order the merchant accepted, and the order.accepted event the outbox recorded for it.
     */
    private OrderEventEnvelope acceptedOrderAt(String restaurantName) throws Exception {
        String email = "staff-order-" + System.nanoTime() + "@example.com";
        customerService.signUp(email, DEMO_PASSWORD, "Staff", "Customer");
        Long customerId = jdbcTemplate.queryForObject("SELECT id FROM customers WHERE email = ?", Long.class, email);
        Long menuItemId = jdbcTemplate.queryForObject("""
                SELECT MIN(menu_items.id)
                FROM menu_items
                JOIN restaurants ON restaurants.id = menu_items.restaurant_id
                WHERE restaurants.name = ?
                """, Long.class, restaurantName);
        cartService.addMenuItemToCart(customerId, menuItemId);
        OrderDto order = cartService.checkout(customerId, "PAID");
        // Only the order.accepted event matters here, so the accept is recorded as a SYSTEM transition.
        orderLifecycleCommands.transitionRestaurantOrder(
                order.id(), order.restaurantId(), OrderStatus.PAID, OrderStatus.ACCEPTED, null, OrderActor.system());
        String payload = jdbcTemplate.queryForObject("""
                SELECT payload
                FROM outbox_events
                WHERE aggregate_type = 'ORDER'
                  AND aggregate_id = ?
                  AND event_type = 'order.accepted'
                """, String.class, order.id());
        return objectMapper.readValue(payload, OrderEventEnvelope.class);
    }


    private int riderLoad(long riderId) {
        return jdbcTemplate.queryForObject("SELECT active_deliveries FROM riders WHERE id = ?", Integer.class, riderId);
    }


    private String orderStatus(Long orderId) {
        return jdbcTemplate.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId);
    }


    private int deliveryCount(Long orderId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM deliveries WHERE order_id = ?", Integer.class, orderId);
    }


    private String deliveryStatus(Long deliveryId) {
        return jdbcTemplate.queryForObject("SELECT status FROM deliveries WHERE id = ?", String.class, deliveryId);
    }


    private List<String> deliveryEventTypes(Long deliveryId) {
        return jdbcTemplate.queryForList(
                "SELECT event_type FROM outbox_events WHERE aggregate_type = 'DELIVERY' AND aggregate_id = ? ORDER BY id",
                String.class,
                deliveryId
        );
    }


    private Long restaurantId(String name) {
        return jdbcTemplate.queryForObject("SELECT id FROM restaurants WHERE name = ?", Long.class, name);
    }


    private long riderIdOf(String email) {
        return jdbcTemplate.queryForObject("""
                SELECT riders.id
                FROM riders
                JOIN customers ON customers.id = riders.customer_id
                WHERE customers.email = ?
                """, Long.class, email);
    }


    private static String merchantBody(String email, String password, Long restaurantId) {
        return """
                {"email": "%s", "password": "%s", "first_name": "Olive", "last_name": "Owner", "restaurant_id": %d}
                """.formatted(email, password, restaurantId);
    }


    private static String riderBody(String email, String password) {
        return """
                {"email": "%s", "password": "%s", "first_name": "Dana", "last_name": "Rider", "phone": "(408) 555-0199"}
                """.formatted(email, password);
    }


    private JsonNode json(org.springframework.test.web.servlet.ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }


    private MockHttpSession signUpAndLogin() throws Exception {
        String email = "matrix-" + System.nanoTime() + "@example.com";
        customerService.signUp(email, DEMO_PASSWORD, "Matrix", "Customer");
        return login(email, DEMO_PASSWORD);
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
