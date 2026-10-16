package com.laioffer.onlineorder.staff;


import com.laioffer.onlineorder.model.OrderDto;
import com.laioffer.onlineorder.service.CartService;
import com.laioffer.onlineorder.service.CustomerService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;


import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;


import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;


/**
 * Staff provisioning and the riders' own actions in the operations audit log: every change writes one entry with
 * the login that made it (the admin, or the rider as ROLE_RIDER) in the change's transaction; a repeat that changes
 * nothing and a refused request (409, 404) write none; no password reaches the entry.
 */
@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=" +
                "org.springframework.boot.session.autoconfigure.SessionAutoConfiguration," +
                "org.springframework.boot.session.data.redis.autoconfigure.SessionDataRedisAutoConfiguration"
})
@AutoConfigureMockMvc
@ActiveProfiles("h2")
class StaffAuditIntegrationTests {

    private static final String ADMIN_EMAIL = "demo@laifood.com";
    private static final String STAFF_PASSWORD = "Staff-secret-91";

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
    private ObjectMapper objectMapper;

    @Autowired
    private CustomerService customerService;

    @Autowired
    private CartService cartService;


    @Test
    void merchantProvisioning_shouldBeAuditedWithTheAdminAndWithoutThePassword() throws Exception {
        MockHttpSession admin = login(ADMIN_EMAIL, "demo123");
        String email = "audit-merchant-" + System.nanoTime() + "@example.com";
        long auditedBefore = countEntries();

        JsonNode merchant = json(mockMvc.perform(post("/admin/merchants")
                        .session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(email, "\"restaurant_id\": 1")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        long merchantId = merchant.get("merchant_id").asLong();
        long customerId = merchant.get("customer_id").asLong();

        // Refused requests roll back and write nothing: the email is taken, the restaurant does not exist.
        mockMvc.perform(post("/admin/merchants")
                        .session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(email.toUpperCase(), "\"restaurant_id\": 1")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_REGISTERED"));
        mockMvc.perform(post("/admin/merchants")
                        .session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("audit-nowhere-" + System.nanoTime() + "@example.com",
                                "\"restaurant_id\": 987654321")))
                .andExpect(status().isNotFound());

        // Deactivating twice is one change; reactivating is another.
        for (boolean active : List.of(false, false, true)) {
            mockMvc.perform(patch("/admin/merchants/{id}", merchantId)
                            .session(admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"active\": " + active + "}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.active").value(active));
        }
        mockMvc.perform(patch("/admin/merchants/{id}", 987_654_321L)
                        .session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\": false}"))
                .andExpect(status().isNotFound());

        List<Map<String, Object>> entries = entriesFor("MERCHANT", merchantId);
        Assertions.assertEquals(
                List.of("merchant.create", "merchant.update", "merchant.update"),
                entries.stream().map(entry -> entry.get("action")).toList()
        );
        Assertions.assertEquals(auditedBefore + 3, countEntries(), "the refused requests wrote no entry");
        for (Map<String, Object> entry : entries) {
            Assertions.assertEquals(adminId(), ((Number) entry.get("actor_customer_id")).longValue());
            Assertions.assertEquals("ROLE_ADMIN", entry.get("actor_role"));
        }
        String createdDetails = (String) entries.get(0).get("details");
        JsonNode created = objectMapper.readTree(createdDetails);
        Assertions.assertEquals(customerId, created.get("customer_id").asLong());
        Assertions.assertEquals(email, created.get("email").asString());
        Assertions.assertEquals(1L, created.get("restaurant_id").asLong());
        Assertions.assertFalse(created.has("password"));
        Assertions.assertFalse(createdDetails.contains(STAFF_PASSWORD));
        JsonNode deactivated = objectMapper.readTree((String) entries.get(1).get("details"));
        Assertions.assertTrue(deactivated.get("from_active").asBoolean());
        Assertions.assertFalse(deactivated.get("to_active").asBoolean());
        Assertions.assertEquals(1L, deactivated.get("restaurant_id").asLong());

        // The same entries over the admin API, newest first.
        mockMvc.perform(get("/admin/audit-log").param("limit", "3").session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].action").value("merchant.update"))
                .andExpect(jsonPath("$[0].target_type").value("MERCHANT"))
                .andExpect(jsonPath("$[0].target_id").value(String.valueOf(merchantId)))
                .andExpect(jsonPath("$[0].details.to_active").value(true))
                .andExpect(jsonPath("$[2].action").value("merchant.create"))
                .andExpect(jsonPath("$[2].details.email").value(email));
    }


    @Test
    void riderProvisioningAndTheRidersOwnActions_shouldBeAuditedWithTheirActor() throws Exception {
        MockHttpSession admin = login(ADMIN_EMAIL, "demo123");
        String email = "audit-rider-" + System.nanoTime() + "@example.com";
        JsonNode rider = json(mockMvc.perform(post("/admin/riders")
                        .session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(email, "\"phone\": \"(408) 555-0142\"")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.on_duty").value(false))
                .andReturn().getResponse().getContentAsString());
        long riderId = rider.get("rider_id").asLong();
        long riderLoginId = rider.get("customer_id").asLong();

        // Admin: on duty (a change), the same again (none), an inactive rider on duty (409, none).
        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(patch("/admin/riders/{id}", riderId)
                            .session(admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"on_duty\": true}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.on_duty").value(true));
        }
        mockMvc.perform(patch("/admin/riders/{id}", riderId)
                        .session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\": false, \"on_duty\": true}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RIDER_INACTIVE"));

        // The rider: off duty (a change), the same again (none), back on duty (a change).
        MockHttpSession riderSession = login(email, STAFF_PASSWORD);
        for (boolean onDuty : List.of(false, false, true)) {
            mockMvc.perform(put("/rider/me/availability")
                            .session(riderSession)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"on_duty\": " + onDuty + "}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.on_duty").value(onDuty));
        }

        List<Map<String, Object>> entries = entriesFor("RIDER", riderId);
        Assertions.assertEquals(
                List.of(
                        "rider.create|ROLE_ADMIN|" + adminId(),
                        "rider.update|ROLE_ADMIN|" + adminId(),
                        "rider.availability|ROLE_RIDER|" + riderLoginId,
                        "rider.availability|ROLE_RIDER|" + riderLoginId
                ),
                entries.stream()
                        .map(entry -> entry.get("action") + "|" + entry.get("actor_role") + "|"
                                + entry.get("actor_customer_id"))
                        .toList()
        );
        String createdDetails = (String) entries.get(0).get("details");
        Assertions.assertEquals(email, objectMapper.readTree(createdDetails).get("email").asString());
        Assertions.assertFalse(createdDetails.contains(STAFF_PASSWORD));
        JsonNode putOnDuty = objectMapper.readTree((String) entries.get(1).get("details"));
        Assertions.assertFalse(putOnDuty.get("from_on_duty").asBoolean());
        Assertions.assertTrue(putOnDuty.get("to_on_duty").asBoolean());
        Assertions.assertTrue(putOnDuty.get("to_active").asBoolean());
        JsonNode offDuty = objectMapper.readTree((String) entries.get(2).get("details"));
        Assertions.assertTrue(offDuty.get("from_on_duty").asBoolean());
        Assertions.assertFalse(offDuty.get("to_on_duty").asBoolean());

        // The rider moves their own delivery: one entry per move, the retry and another rider's delivery none.
        long deliveryId = insertAssignedDelivery(paidOrder(), riderId);
        long otherDeliveryId = insertAssignedDelivery(paidOrder(), insertRider());
        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(post("/rider/deliveries/{id}/picked-up", deliveryId).session(riderSession))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("PICKED_UP"));
        }
        mockMvc.perform(post("/rider/deliveries/{id}/delivered", deliveryId).session(riderSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELIVERED"));
        mockMvc.perform(post("/rider/deliveries/{id}/picked-up", otherDeliveryId).session(riderSession))
                .andExpect(status().isNotFound());

        Assertions.assertEquals(
                List.of(
                        "delivery.picked_up|ROLE_RIDER|" + riderLoginId,
                        "delivery.delivered|ROLE_RIDER|" + riderLoginId
                ),
                entriesFor("DELIVERY", deliveryId).stream()
                        .map(entry -> entry.get("action") + "|" + entry.get("actor_role") + "|"
                                + entry.get("actor_customer_id"))
                        .toList()
        );
        JsonNode delivered = objectMapper.readTree((String) entriesFor("DELIVERY", deliveryId).get(1).get("details"));
        Assertions.assertEquals("PICKED_UP", delivered.get("from_status").asString());
        Assertions.assertEquals("DELIVERED", delivered.get("to_status").asString());
        Assertions.assertEquals(riderId, delivered.get("rider_id").asLong());
        Assertions.assertTrue(entriesFor("DELIVERY", otherDeliveryId).isEmpty());
    }


    @Test
    void reassigningADelivery_shouldBeAuditedWithBothRiders() throws Exception {
        MockHttpSession admin = login(ADMIN_EMAIL, "demo123");
        long fromRiderId = insertRider();
        long toRiderId = insertRider();
        long offDutyRiderId = insertRider();
        jdbcTemplate.update("UPDATE riders SET on_duty = FALSE WHERE id = ?", offDutyRiderId);
        long deliveryId = insertAssignedDelivery(paidOrder(), fromRiderId);

        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(post("/admin/deliveries/{id}/reassign", deliveryId)
                            .session(admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"rider_id\": " + toRiderId + "}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.rider_id").value(toRiderId));
        }
        mockMvc.perform(post("/admin/deliveries/{id}/reassign", deliveryId)
                        .session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rider_id\": " + offDutyRiderId + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RIDER_NOT_DISPATCHABLE"));

        List<Map<String, Object>> entries = entriesFor("DELIVERY", deliveryId);
        Assertions.assertEquals(1, entries.size(), "the retry and the refused reassignment are not actions");
        Assertions.assertEquals("delivery.reassign", entries.get(0).get("action"));
        Assertions.assertEquals("ROLE_ADMIN", entries.get(0).get("actor_role"));
        Assertions.assertEquals(adminId(), ((Number) entries.get(0).get("actor_customer_id")).longValue());
        JsonNode details = objectMapper.readTree((String) entries.get(0).get("details"));
        Assertions.assertEquals(fromRiderId, details.get("from_rider_id").asLong());
        Assertions.assertEquals(toRiderId, details.get("to_rider_id").asLong());
        Assertions.assertEquals("ASSIGNED", details.get("status").asString());
    }


    private String createBody(String email, String extraField) {
        return "{\"email\": \"" + email + "\", \"password\": \"" + STAFF_PASSWORD + "\", "
                + "\"first_name\": \"Audit\", \"last_name\": \"Staff\", " + extraField + "}";
    }


    private OrderDto paidOrder() {
        String email = "audit-staff-customer-" + System.nanoTime() + "@example.com";
        customerService.signUp(email, "demo123", "Audit", "Customer");
        Long customerId = jdbcTemplate.queryForObject("SELECT id FROM customers WHERE email = ?", Long.class, email);
        cartService.addMenuItemToCart(customerId, 1L);
        return cartService.checkout(customerId, "PAID");
    }


    /**
     * An active, on-duty rider without a login.
     */
    private long insertRider() {
        String name = "Audit Staff Rider " + System.nanoTime();
        jdbcTemplate.update(
                "INSERT INTO riders (name, active, on_duty, active_deliveries) VALUES (?, TRUE, TRUE, 0)", name);
        return jdbcTemplate.queryForObject("SELECT id FROM riders WHERE name = ?", Long.class, name);
    }


    /**
     * The delivery the dispatch consumer would have assigned for the order (Kafka is off here), with its rider's load.
     */
    private long insertAssignedDelivery(OrderDto order, long riderId) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("""
                INSERT INTO deliveries (order_id, customer_id, restaurant_id, rider_id, status, assigned_at, updated_at)
                SELECT id, customer_id, restaurant_id, ?, 'ASSIGNED', ?, ?
                FROM orders
                WHERE id = ?
                """, riderId, now, now, order.id());
        jdbcTemplate.update("UPDATE riders SET active_deliveries = active_deliveries + 1 WHERE id = ?", riderId);
        return jdbcTemplate.queryForObject("SELECT id FROM deliveries WHERE order_id = ?", Long.class, order.id());
    }


    private long adminId() {
        return jdbcTemplate.queryForObject("SELECT id FROM customers WHERE email = ?", Long.class, ADMIN_EMAIL);
    }


    private List<Map<String, Object>> entriesFor(String targetType, long targetId) {
        return jdbcTemplate.queryForList("""
                SELECT actor_customer_id, actor_role, action, details, trace_id
                FROM audit_log
                WHERE target_type = ? AND target_id = ?
                ORDER BY id
                """, targetType, String.valueOf(targetId));
    }


    private long countEntries() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_log", Long.class);
    }


    private JsonNode json(String body) {
        return objectMapper.readTree(body);
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
