package com.laioffer.onlineorder;


import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.laioffer.onlineorder.aftersales.AfterSalesActor;
import com.laioffer.onlineorder.aftersales.AfterSalesCaseRepository;
import com.laioffer.onlineorder.aftersales.AfterSalesTransitions;
import com.laioffer.onlineorder.aftersales.AfterSalesTrigger;
import com.laioffer.onlineorder.audit.AuditActor;
import com.laioffer.onlineorder.audit.AuditLogService;
import com.laioffer.onlineorder.model.OrderDto;
import com.laioffer.onlineorder.service.CartService;
import com.laioffer.onlineorder.service.CustomerService;
import com.laioffer.onlineorder.service.OrderService;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;


import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;


import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;


/**
 * The operations audit log: each audited admin action writes one entry with the admin, the role and the request's
 * trace id in the action's transaction; refused actions and idempotent retries write none; GET /admin/audit-log is
 * admin-only and pages newest first by id.
 */
@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=" +
                "org.springframework.boot.session.autoconfigure.SessionAutoConfiguration," +
                "org.springframework.boot.session.data.redis.autoconfigure.SessionDataRedisAutoConfiguration"
})
@AutoConfigureMockMvc
// Tracing propagation is off in Spring Boot tests unless asked for; the entries' trace_id needs W3C extraction.
@AutoConfigureTracing
@ActiveProfiles("h2")
class AuditLogIntegrationTests {

    private static final String ADMIN_EMAIL = "demo@laifood.com";

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
    private AuditLogService auditLogService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private CustomerService customerService;

    @Autowired
    private CartService cartService;

    @Autowired
    private OrderService orderService;

    @Autowired
    private AfterSalesTransitions afterSalesTransitions;

    @Autowired
    private AfterSalesCaseRepository afterSalesCaseRepository;


    @Test
    void orderStatusChange_shouldBeAuditedOnceWithTheAdminAndTheRequestsTraceId() throws Exception {
        OrderDto order = paidOrder();
        MockHttpSession admin = login(ADMIN_EMAIL, "demo123");
        String traceId = "5c2f1e4d3b2a19087f6e5d4c3b2a1908";

        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(patch("/orders/{id}/status", order.id())
                            .session(admin)
                            .header("Idempotency-Key", "audit-status-" + order.id())
                            .header("traceparent", "00-" + traceId + "-00f067aa0ba902b7-01")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\": \"ACCEPTED\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("ACCEPTED"));
        }

        List<Map<String, Object>> entries = entriesFor("ORDER", order.id());
        Assertions.assertEquals(1, entries.size(), "the retry with the same key is not a second action");
        Map<String, Object> entry = entries.get(0);
        Assertions.assertEquals(adminId(), ((Number) entry.get("actor_customer_id")).longValue());
        Assertions.assertEquals("ROLE_ADMIN", entry.get("actor_role"));
        Assertions.assertEquals("order.status.update", entry.get("action"));
        Assertions.assertEquals(traceId, entry.get("trace_id"));
        JsonNode details = objectMapper.readTree((String) entry.get("details"));
        Assertions.assertEquals("PAID", details.get("from_status").asString());
        Assertions.assertEquals("ACCEPTED", details.get("to_status").asString());
        Assertions.assertTrue(details.get("changed").asBoolean());
        Assertions.assertEquals("audit-status-" + order.id(), details.get("idempotency_key").asString());

        // The order history carries the same admin and the same trace.
        Assertions.assertEquals(
                List.of("ADMIN|" + adminId() + "|" + traceId),
                jdbcTemplate.queryForList("""
                        SELECT actor_type || '|' || actor_id || '|' || trace_id
                        FROM order_status_history
                        WHERE order_id = ? AND to_status = 'ACCEPTED'
                        """, String.class, order.id())
        );
    }


    @Test
    void outboxRetry_shouldBeAuditedWithTheStateTheRetryCleared() throws Exception {
        long eventId = insertOutboxEvent("FAILED", 10, "Kafka unavailable");
        MockHttpSession admin = login(ADMIN_EMAIL, "demo123");

        mockMvc.perform(post("/admin/outbox/events/{id}/retry", eventId).session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));

        List<Map<String, Object>> entries = entriesFor("OUTBOX_EVENT", eventId);
        Assertions.assertEquals(1, entries.size());
        Assertions.assertEquals("outbox_event.retry", entries.get(0).get("action"));
        Assertions.assertEquals(adminId(), ((Number) entries.get(0).get("actor_customer_id")).longValue());
        JsonNode details = objectMapper.readTree((String) entries.get(0).get("details"));
        Assertions.assertEquals(10, details.get("previous_attempts").asInt());
        Assertions.assertEquals("Kafka unavailable", details.get("previous_last_error").asString());
        Assertions.assertEquals("order.created", details.get("event_type").asString());
    }


    @Test
    void catalogUpdates_shouldBeAuditedWithTheFieldsTheyChanged() throws Exception {
        MockHttpSession admin = login(ADMIN_EMAIL, "demo123");
        long menuItemId = jdbcTemplate.queryForObject("SELECT MAX(id) FROM menu_items", Long.class);
        long restaurantId = jdbcTemplate.queryForObject("SELECT restaurant_id FROM menu_items WHERE id = ?", Long.class, menuItemId);

        mockMvc.perform(patch("/admin/menu-items/{id}", menuItemId)
                        .session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"price\": 12.34}"))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/admin/restaurants/{id}", restaurantId)
                        .session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\": \"(408) 555-0199\"}"))
                .andExpect(status().isOk());

        Map<String, Object> menuEntry = entriesFor("MENU_ITEM", menuItemId).get(0);
        Assertions.assertEquals("menu_item.update", menuEntry.get("action"));
        JsonNode menuDetails = objectMapper.readTree((String) menuEntry.get("details"));
        Assertions.assertEquals(restaurantId, menuDetails.get("restaurant_id").asLong());
        Assertions.assertEquals("12.34", menuDetails.get("changes").get("price").asString());
        Assertions.assertEquals("USD", menuDetails.get("changes").get("currency").asString(), "a price names its currency");
        Assertions.assertFalse(menuDetails.get("changes").has("name"), "only the fields the request set");

        Map<String, Object> restaurantEntry = entriesFor("RESTAURANT", restaurantId).get(0);
        Assertions.assertEquals("restaurant.update", restaurantEntry.get("action"));
        Assertions.assertEquals("(408) 555-0199", objectMapper.readTree((String) restaurantEntry.get("details"))
                .get("changes").get("phone").asString());
    }


    @Test
    void simulatedRiderActions_shouldBeAuditedOnceEachWithTheAdmin() throws Exception {
        long deliveryId = insertAssignedDelivery(paidOrder());
        MockHttpSession admin = login(ADMIN_EMAIL, "demo123");

        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(post("/admin/deliveries/{id}/picked-up", deliveryId).session(admin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("PICKED_UP"));
        }
        mockMvc.perform(post("/admin/deliveries/{id}/delivered", deliveryId).session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELIVERED"));
        // A delivered delivery cannot be picked up again: refused, rolled back, not audited.
        mockMvc.perform(post("/admin/deliveries/{id}/picked-up", deliveryId).session(admin))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/admin/deliveries/{id}/delivered", 987_654_321L).session(admin))
                .andExpect(status().isNotFound());

        List<Map<String, Object>> entries = entriesFor("DELIVERY", deliveryId);
        Assertions.assertEquals(
                List.of("delivery.picked_up", "delivery.delivered"),
                entries.stream().map(entry -> entry.get("action")).toList(),
                "the repeated pick-up is not a second action"
        );
        for (Map<String, Object> entry : entries) {
            Assertions.assertEquals(adminId(), ((Number) entry.get("actor_customer_id")).longValue());
            Assertions.assertEquals("ROLE_ADMIN", entry.get("actor_role"));
        }
        JsonNode delivered = objectMapper.readTree((String) entries.get(1).get("details"));
        Assertions.assertEquals("PICKED_UP", delivered.get("from_status").asString());
        Assertions.assertEquals("DELIVERED", delivered.get("to_status").asString());
        Assertions.assertTrue(delivered.get("rider_id").asLong() > 0);
        Assertions.assertTrue(entriesFor("DELIVERY", 987_654_321L).isEmpty());
    }


    /**
     * An arbitration is one action per key: the replay is not audited again, and neither is a decision the case's
     * status refuses. The entry and the case history name the same admin and trace.
     */
    @Test
    void afterSalesArbitration_shouldBeAuditedOncePerKeyWithTheAdminAndTheRequestsTraceId() throws Exception {
        long caseId = escalatedAfterSalesCase();
        MockHttpSession admin = login(ADMIN_EMAIL, "demo123");
        String traceId = "6d3e2f5c4b3a2a1908f7e6d5c4b3a291";

        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(post("/admin/after-sales/{id}/arbitrate", caseId)
                            .session(admin)
                            .header("Idempotency-Key", "audit-arbitrate-" + caseId)
                            .header("traceparent", "00-" + traceId + "-00f067aa0ba902b7-01")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"decision\": \"REJECT\", \"note\": \"The photos show a complete order.\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("CLOSED"));
        }
        mockMvc.perform(post("/admin/after-sales/{id}/arbitrate", caseId)
                        .session(admin)
                        .header("Idempotency-Key", "audit-arbitrate-again-" + caseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\": \"FULL\", \"note\": \"Refund it after all.\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AFTER_SALES_STATUS_CONFLICT"));

        List<Map<String, Object>> entries = entriesFor("AFTER_SALES_CASE", caseId);
        Assertions.assertEquals(1, entries.size(), "the replay and the refused decision are not actions");
        Map<String, Object> entry = entries.get(0);
        Assertions.assertEquals("after_sales_case.arbitrate", entry.get("action"));
        Assertions.assertEquals(adminId(), ((Number) entry.get("actor_customer_id")).longValue());
        Assertions.assertEquals("ROLE_ADMIN", entry.get("actor_role"));
        Assertions.assertEquals(traceId, entry.get("trace_id"));
        JsonNode details = objectMapper.readTree((String) entry.get("details"));
        Assertions.assertEquals("REJECT", details.get("decision").asString());
        Assertions.assertEquals("IN_ARBITRATION", details.get("from_status").asString());
        Assertions.assertEquals("CLOSED", details.get("to_status").asString());
        Assertions.assertEquals("audit-arbitrate-" + caseId, details.get("idempotency_key").asString());
        Assertions.assertFalse(details.has("amount"), "a rejection approves no amount");
        Assertions.assertEquals(
                List.of("ADMIN|" + adminId() + "|" + traceId),
                jdbcTemplate.queryForList("""
                        SELECT actor_type || '|' || actor_id || '|' || trace_id
                        FROM after_sales_case_history
                        WHERE case_id = ? AND action = 'ADMIN_REJECT'
                        """, String.class, caseId)
        );
    }


    /**
     * A refund retry is audited only when it restarts a FAILED refund, with the attempts and the error it leaves
     * behind; a retry of a refund already PENDING and a refused one are not actions.
     */
    @Test
    void refundRetry_shouldBeAuditedOnlyWhenItRestartsAFailedRefund() throws Exception {
        long caseId = escalatedAfterSalesCase();
        MockHttpSession admin = login(ADMIN_EMAIL, "demo123");
        mockMvc.perform(post("/admin/after-sales/{id}/arbitrate", caseId)
                        .session(admin)
                        .header("Idempotency-Key", "audit-full-" + caseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\": \"FULL\", \"note\": \"Refund it.\"}"))
                .andExpect(status().isOk());
        jdbcTemplate.update("""
                UPDATE refunds
                SET status = 'FAILED', attempts = 8, next_attempt_at = NULL, last_error = 'card account closed',
                    version = version + 1
                WHERE case_id = ?
                """, caseId);
        long refundId = jdbcTemplate.queryForObject("SELECT id FROM refunds WHERE case_id = ?", Long.class, caseId);

        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(post("/admin/refunds/{id}/retry", refundId).session(admin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("PENDING"));
        }
        jdbcTemplate.update("""
                UPDATE refunds
                SET status = 'SUCCEEDED', provider_reference = 'SIMREF-audit', succeeded_at = ?,
                    version = version + 1
                WHERE id = ?
                """, LocalDateTime.now(), refundId);
        mockMvc.perform(post("/admin/refunds/{id}/retry", refundId).session(admin))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REFUND_STATUS_CONFLICT"));

        List<Map<String, Object>> entries = entriesFor("REFUND", refundId);
        Assertions.assertEquals(1, entries.size(), "only the retry that restarted the refund");
        Assertions.assertEquals("refund.retry", entries.get(0).get("action"));
        Assertions.assertEquals(adminId(), ((Number) entries.get(0).get("actor_customer_id")).longValue());
        Assertions.assertEquals("ROLE_ADMIN", entries.get(0).get("actor_role"));
        JsonNode details = objectMapper.readTree((String) entries.get(0).get("details"));
        Assertions.assertEquals(caseId, details.get("case_id").asLong());
        Assertions.assertEquals(8, details.get("previous_attempts").asInt());
        Assertions.assertEquals("card account closed", details.get("last_error").asString());
        Assertions.assertEquals(
                jdbcTemplate.queryForObject("SELECT order_id FROM after_sales_cases WHERE id = ?", Long.class, caseId),
                details.get("order_id").asLong(),
                "the order of the refunded case");
    }


    @Test
    void refusedAdminActions_shouldNotBeAudited() throws Exception {
        MockHttpSession admin = login(ADMIN_EMAIL, "demo123");
        OrderDto order = paidOrder();
        long processingEventId = insertOutboxEvent("PROCESSING", 1, null);
        long auditedBefore = countEntries();

        mockMvc.perform(patch("/orders/{id}/status", order.id())
                        .session(admin)
                        .header("Idempotency-Key", "audit-invalid-" + order.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"COMPLETED\"}"))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/admin/outbox/events/{id}/retry", processingEventId).session(admin))
                .andExpect(status().isBadRequest());
        mockMvc.perform(patch("/admin/menu-items/{id}", 987_654_321L)
                        .session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"price\": 1.00}"))
                .andExpect(status().isNotFound());

        Assertions.assertEquals(auditedBefore, countEntries());
    }


    @Test
    void record_shouldOnlyWriteInsideTheCallersTransactionAndRollBackWithIt() {
        Assertions.assertThrows(IllegalTransactionStateException.class, () -> auditLogService.record(
                AuditActor.system(), "test.outside", "TEST", 1L, Map.of()));

        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> {
            auditLogService.record(AuditActor.admin(1L), "test.rolled_back", "TEST", 2L, Map.of("k", "v"));
            status.setRollbackOnly();
        });
        Assertions.assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> {
            auditLogService.record(AuditActor.admin(1L), "test.failed", "TEST", 3L, Map.of("k", "v"));
            throw new IllegalStateException("the action failed after the audit entry was written");
        }));

        Assertions.assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE action IN ('test.outside', 'test.rolled_back', 'test.failed')",
                Long.class));
    }


    @Test
    void auditLogEndpoint_shouldBeAdminOnlyAndPageNewestFirstByBeforeId() throws Exception {
        List<Long> seeded = new ArrayList<>();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        for (int i = 0; i < 5; i++) {
            long target = i;
            transaction.executeWithoutResult(status -> auditLogService.record(
                    AuditActor.admin(1L), "test.page", "PAGE_TEST", target, Map.of("n", target)));
            seeded.add(jdbcTemplate.queryForObject(
                    "SELECT MAX(id) FROM audit_log WHERE action = 'test.page'", Long.class));
        }

        mockMvc.perform(get("/admin/audit-log")).andExpect(status().isUnauthorized());
        MockHttpSession customer = login(signUp(), "demo123");
        mockMvc.perform(get("/admin/audit-log").session(customer))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        MockHttpSession admin = login(ADMIN_EMAIL, "demo123");
        long newestSeeded = seeded.get(4);
        mockMvc.perform(get("/admin/audit-log").param("limit", "2").param("before_id", String.valueOf(newestSeeded + 1))
                        .session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(seeded.get(4)))
                .andExpect(jsonPath("$[1].id").value(seeded.get(3)))
                .andExpect(jsonPath("$[0].action").value("test.page"))
                .andExpect(jsonPath("$[0].actor_customer_id").value(1))
                .andExpect(jsonPath("$[0].actor_role").value("ROLE_ADMIN"))
                .andExpect(jsonPath("$[0].target_type").value("PAGE_TEST"))
                .andExpect(jsonPath("$[0].target_id").value("4"))
                .andExpect(jsonPath("$[0].details.n").value(4))
                .andExpect(jsonPath("$[0]", Matchers.hasKey("trace_id")))
                .andExpect(jsonPath("$[0].occurred_at").isNotEmpty());
        mockMvc.perform(get("/admin/audit-log").param("limit", "2").param("before_id", String.valueOf(seeded.get(3)))
                        .session(admin))
                .andExpect(jsonPath("$[0].id").value(seeded.get(2)))
                .andExpect(jsonPath("$[1].id").value(seeded.get(1)));
        // Without before_id the newest entries come first; an oversized limit is clamped, not rejected.
        String newest = mockMvc.perform(get("/admin/audit-log").param("limit", "1000").session(admin))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<Long> ids = new ArrayList<>();
        objectMapper.readTree(newest).forEach(node -> ids.add(node.get("id").asLong()));
        Assertions.assertTrue(ids.size() <= 200 && ids.containsAll(seeded), ids.toString());
        for (int i = 1; i < ids.size(); i++) {
            Assertions.assertTrue(ids.get(i - 1) > ids.get(i), "newest first: " + ids);
        }
    }


    private OrderDto paidOrder() {
        String email = signUp();
        Long customerId = jdbcTemplate.queryForObject("SELECT id FROM customers WHERE email = ?", Long.class, email);
        cartService.addMenuItemToCart(customerId, 1L);
        return cartService.checkout(customerId, "PAID");
    }


    private String signUp() {
        String email = "audit-" + System.nanoTime() + "@example.com";
        customerService.signUp(email, "demo123", "Audit", "Customer");
        return email;
    }


    /**
     * A paid, completed order's whole-order request that the merchant rejected and the customer escalated: a case in
     * the arbitration queue.
     */
    private long escalatedAfterSalesCase() throws Exception {
        String email = signUp();
        Long customerId = jdbcTemplate.queryForObject("SELECT id FROM customers WHERE email = ?", Long.class, email);
        cartService.addMenuItemToCart(customerId, 1L);
        OrderDto order = cartService.checkout(customerId, "PAID");
        for (String status : List.of("ACCEPTED", "PREPARING", "COMPLETED")) {
            orderService.transitionOrderStatus(order.id(), status);
        }
        MockHttpSession customer = login(email, "demo123");
        String opened = mockMvc.perform(post("/orders/{id}/after-sales", order.id())
                        .session(customer)
                        .header("Idempotency-Key", "audit-open-" + order.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"scope": "WHOLE_ORDER", "reason_code": "MISSING_ITEM", "description": "Missing."}
                                """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long caseId = objectMapper.readTree(opened).get("id").asLong();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> Assertions.assertTrue(
                afterSalesTransitions.fire(afterSalesCaseRepository.lockById(caseId), new AfterSalesTransitions.Command(
                        AfterSalesTrigger.MERCHANT_REJECT, AfterSalesActor.MERCHANT, null, null,
                        "The order was complete.", null)).applied()));
        mockMvc.perform(post("/after-sales/cases/{id}/escalate", caseId)
                        .session(customer)
                        .header("Idempotency-Key", "audit-escalate-" + caseId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_ARBITRATION"));
        return caseId;
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


    private long insertOutboxEvent(String status, int attempts, String lastError) {
        String eventId = "audit-outbox-" + System.nanoTime();
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("""
                INSERT INTO outbox_events (aggregate_type, aggregate_id, event_id, topic, event_key, event_type, payload,
                                           status, attempts, last_error, created_at, updated_at)
                VALUES ('ORDER', 100, ?, 'order-events', '100', 'order.created', '{}', ?, ?, ?, ?, ?)
                """, eventId, status, attempts, lastError, now, now);
        return jdbcTemplate.queryForObject("SELECT id FROM outbox_events WHERE event_id = ?", Long.class, eventId);
    }


    /**
     * The delivery the dispatch consumer would have assigned for the order (Kafka is off here), with its rider's load.
     */
    private long insertAssignedDelivery(OrderDto order) {
        String riderName = "Audit Rider " + System.nanoTime();
        jdbcTemplate.update("INSERT INTO riders (name, active_deliveries) VALUES (?, 1)", riderName);
        long riderId = jdbcTemplate.queryForObject("SELECT id FROM riders WHERE name = ?", Long.class, riderName);
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("""
                INSERT INTO deliveries (order_id, customer_id, restaurant_id, rider_id, status, assigned_at, updated_at)
                SELECT id, customer_id, restaurant_id, ?, 'ASSIGNED', ?, ?
                FROM orders
                WHERE id = ?
                """, riderId, now, now, order.id());
        return jdbcTemplate.queryForObject("SELECT id FROM deliveries WHERE order_id = ?", Long.class, order.id());
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
