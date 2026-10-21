package com.laioffer.onlineorder;


import com.laioffer.onlineorder.aftersales.AfterSalesActor;
import com.laioffer.onlineorder.aftersales.AfterSalesCaseRepository;
import com.laioffer.onlineorder.aftersales.AfterSalesTransitions;
import com.laioffer.onlineorder.aftersales.AfterSalesTrigger;
import com.laioffer.onlineorder.fulfilment.dispatch.DispatchService;
import com.laioffer.onlineorder.fulfilment.merchant.MerchantAfterSalesInboxService;
import com.laioffer.onlineorder.fulfilment.merchant.MerchantInboxService;
import com.laioffer.onlineorder.messaging.AfterSalesEventEnvelope;
import com.laioffer.onlineorder.messaging.OrderEventEnvelope;
import com.laioffer.onlineorder.messaging.contract.EventContracts;
import com.laioffer.onlineorder.messaging.contract.EventTopic;
import com.laioffer.onlineorder.model.OrderDto;
import com.laioffer.onlineorder.money.Money;
import com.laioffer.onlineorder.service.CartService;
import com.laioffer.onlineorder.service.OrderService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;


import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;


@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=" +
                "org.springframework.boot.session.autoconfigure.SessionAutoConfiguration," +
                "org.springframework.boot.session.data.redis.autoconfigure.SessionDataRedisAutoConfiguration"
})
@AutoConfigureMockMvc
// Spring Boot tests set management.tracing.export.enabled=false unless asked for, which also switches W3C propagation
// off; the trace id contract needs W3C extraction. Span export stays off (management.tracing.export.otlp.enabled
// follows TRACING_EXPORT_ENABLED, false by default).
@AutoConfigureTracing
@ActiveProfiles("h2")
class ApiContractIntegrationTests {

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
    private CartService cartService;

    @Autowired
    private OrderService orderService;

    @Autowired
    private AfterSalesTransitions afterSalesTransitions;

    @Autowired
    private AfterSalesCaseRepository afterSalesCaseRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private MerchantAfterSalesInboxService merchantAfterSalesInboxService;

    @Autowired
    private EventContracts eventContracts;

    @Autowired
    private MerchantInboxService merchantInboxService;

    @Autowired
    private DispatchService dispatchService;


    /**
     * Error bodies and the X-Trace-Id header carry the OpenTelemetry trace id; callers join a trace with a W3C
     * traceparent header.
     */
    private static String traceParent(String traceId) {
        return "00-" + traceId + "-00f067aa0ba902b7-01";
    }


    @Test
    void openApiDocs_shouldBePublicAndDescribeApiPaths() throws Exception {
        mockMvc.perform(get("/v3/api-docs")
                        .header("traceparent", traceParent("0af7651916cd43dd8448eb211c80319c")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").exists())
                .andExpect(jsonPath("$.paths['/cart']").exists());
    }


    @Test
    void signup_whenRequestBodyIsInvalid_shouldReturnUnifiedValidationErrorWithTraceId() throws Exception {
        mockMvc.perform(post("/signup")
                        .header("traceparent", traceParent("4bf92f3577b34da6a3ce929d0e0e4736"))
                        // Ignored while a span is in scope: the OpenTelemetry trace id wins.
                        .header("X-Trace-Id", "trace-validation-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "",
                                  "password": ""
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("X-Trace-Id", "4bf92f3577b34da6a3ce929d0e0e4736"))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("Request validation failed"))
                .andExpect(jsonPath("$.trace_id").value("4bf92f3577b34da6a3ce929d0e0e4736"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.path").value("/signup"))
                .andExpect(jsonPath("$.timestamp").exists());
    }


    @Test
    void signup_whenEmailAlreadyExists_shouldPreserveConflictStatusWithUnifiedError() throws Exception {
        String email = "contract-" + System.nanoTime() + "@example.com";
        String body = """
                {
                  "email": "%s",
                  "password": "demo123",
                  "first_name": "Contract",
                  "last_name": "User"
                }
                """.formatted(email);

        mockMvc.perform(post("/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/signup")
                        .header("traceparent", traceParent("5b8efff798038103d269b633813fc60c"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(header().string("X-Trace-Id", "5b8efff798038103d269b633813fc60c"))
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.message").value("Email already exists"))
                .andExpect(jsonPath("$.trace_id").value("5b8efff798038103d269b633813fc60c"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.path").value("/signup"));
    }


    @Test
    void addToCart_whenCartHoldsAnotherRestaurant_shouldReturnConflictWithCartRestaurantCode() throws Exception {
        String email = "cart-contract-" + System.nanoTime() + "@example.com";
        mockMvc.perform(post("/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "demo123", "first_name": "Cart", "last_name": "User"}
                                """.formatted(email)))
                .andExpect(status().isCreated());
        MockHttpSession session = (MockHttpSession) mockMvc.perform(post("/login")
                        .param("username", email)
                        .param("password", "demo123"))
                .andExpect(status().isOk())
                .andReturn()
                .getRequest()
                .getSession(false);
        Long otherRestaurantItemId = jdbcTemplate.queryForObject(
                "SELECT MIN(id) FROM menu_items WHERE restaurant_id <> (SELECT restaurant_id FROM menu_items WHERE id = 1)",
                Long.class
        );

        mockMvc.perform(post("/cart")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"menu_id\": 1}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/cart")
                        .session(session)
                        .header("traceparent", traceParent("c0a1f3e2d4b5968778695a4b3c2d1e0f"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"menu_id\": %d}".formatted(otherRestaurantItemId)))
                .andExpect(status().isConflict())
                .andExpect(header().string("X-Trace-Id", "c0a1f3e2d4b5968778695a4b3c2d1e0f"))
                .andExpect(jsonPath("$.code").value("CART_RESTAURANT_CONFLICT"))
                .andExpect(jsonPath("$.trace_id").value("c0a1f3e2d4b5968778695a4b3c2d1e0f"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.path").value("/cart"));
    }


    @Test
    void protectedEndpoint_whenUnauthenticated_shouldReturnUnifiedUnauthorizedError() throws Exception {
        mockMvc.perform(get("/cart")
                        .header("traceparent", traceParent("a3ce929d0e0e47364bf92f3577b34da6")))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("X-Trace-Id", "a3ce929d0e0e47364bf92f3577b34da6"))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("Authentication required"))
                .andExpect(jsonPath("$.trace_id").value("a3ce929d0e0e47364bf92f3577b34da6"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.path").value("/cart"));
    }


    /**
     * The field order and formats of the API's JSON are those of Jackson 2 (declaration order, ISO-8601 instants):
     * clients and the catalog ETag see the same bytes after the Jackson 3 switch.
     */
    @Test
    void jsonBodies_shouldKeepTheFieldOrderOfTheirDeclaration() throws Exception {
        String error = mockMvc.perform(get("/cart"))
                .andExpect(status().isUnauthorized())
                .andReturn()
                .getResponse()
                .getContentAsString();
        Assertions.assertEquals(List.of("code", "message", "trace_id", "status", "path", "timestamp"), keys(error), error);
        Assertions.assertTrue(
                error.matches(".*\"timestamp\":\"\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z\"}"),
                () -> "ISO-8601 instant expected: " + error
        );

        String menu = mockMvc.perform(get("/restaurant/1/menu"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String firstItem = menu.substring(0, menu.indexOf('}') + 1);
        Assertions.assertEquals(
                List.of("id", "restaurant_id", "name", "description", "price", "currency", "image_url"),
                keys(firstItem),
                firstItem
        );
    }


    /**
     * Every amount in a response has a sibling currency field (ISO 4217); the amounts keep their names and stay JSON
     * numbers.
     */
    @Test
    void moneyFields_shouldEachComeWithTheirCurrency() throws Exception {
        mockMvc.perform(get("/restaurants/menu"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].currency").value("USD"))
                .andExpect(jsonPath("$[0].menu_items[0].price").isNumber())
                .andExpect(jsonPath("$[0].menu_items[0].currency").value("USD"));
        mockMvc.perform(get("/restaurant/1/menu"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].price").value(4.89))
                .andExpect(jsonPath("$[0].currency").value("USD"));

        MockHttpSession session = signUpAndLogIn("money-contract-");
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/cart")
                            .session(session)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"menu_id\": 1}"))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(get("/cart").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total_price").value(9.78))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.order_items[0].price").value(4.89))
                .andExpect(jsonPath("$.order_items[0].currency").value("USD"));
        mockMvc.perform(post("/cart/checkout")
                        .session(session)
                        .header("Idempotency-Key", "money-contract-" + System.nanoTime()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total_price").value(9.78))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.items[0].price").value(4.89))
                .andExpect(jsonPath("$.items[0].currency").value("USD"));
        mockMvc.perform(get("/orders").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].total_price").value(9.78))
                .andExpect(jsonPath("$[0].currency").value("USD"))
                .andExpect(jsonPath("$[0].items[0].currency").value("USD"));
        mockMvc.perform(get("/cart").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total_price").value(0))
                .andExpect(jsonPath("$.currency").value("USD"));
    }


    /**
     * The after-sales bodies of ADR 0002 section 11.5 in declaration order (null fields omitted), every amount with a
     * sibling currency, and order numbers as strings: a request just opened, the same case once its counter-offer was
     * accepted (offer, approval, merchant answer and refund), its lines, history steps and refund, and the overview
     * with an order that can get a request. The merchant's view of the counter-offered case and the admin's refund list
     * (last_error included) are locked the same way.
     */
    @Test
    void afterSalesBodies_shouldKeepTheFieldOrderOfTheirDeclaration() throws Exception {
        String email = "after-sales-contract-" + System.nanoTime() + "@example.com";
        MockHttpSession session = signUpAndLogInAs(email);
        Long customerId = jdbcTemplate.queryForObject("SELECT id FROM customers WHERE email = ?", Long.class, email);
        cartService.addMenuItemToCart(customerId, 1L);
        cartService.addMenuItemToCart(customerId, 1L);
        OrderDto order = cartService.checkout(customerId, "PAID");
        for (String status : List.of("ACCEPTED", "PREPARING", "COMPLETED")) {
            orderService.transitionOrderStatus(order.id(), status);
        }

        JsonNode overview = json(mockMvc.perform(get("/after-sales/cases").session(session))
                .andExpect(status().isOk()));
        Assertions.assertEquals(List.of("cases", "eligible_orders"), List.copyOf(overview.propertyNames()));
        JsonNode eligible = overview.get("eligible_orders").get(0);
        Assertions.assertEquals(List.of("order_id", "order_no", "attempt_no", "request_deadline"),
                List.copyOf(eligible.propertyNames()));
        Assertions.assertTrue(eligible.get("order_no").isString());

        JsonNode opened = json(mockMvc.perform(post("/orders/{id}/after-sales", order.id())
                        .session(session)
                        .header("Idempotency-Key", "contract-open-" + order.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"scope": "WHOLE_ORDER", "reason_code": "MISSING_ITEM", "description": "Missing."}
                                """))
                .andExpect(status().isOk()));
        Assertions.assertEquals(
                List.of("id", "order_id", "order_no", "restaurant_id", "kind", "attempt_no", "status", "reason_code",
                        "description", "requested_amount", "currency", "lines", "deadline_at", "history",
                        "created_at", "updated_at"),
                List.copyOf(opened.propertyNames())
        );
        Assertions.assertTrue(opened.get("order_no").isString());
        Assertions.assertEquals(9.78, opened.get("requested_amount").asDouble());
        Assertions.assertEquals("USD", opened.get("currency").asString());
        Assertions.assertEquals(List.of("order_item_id", "menu_item_name", "quantity", "unit_price", "amount",
                "currency"), List.copyOf(opened.get("lines").get(0).propertyNames()));
        Assertions.assertEquals("USD", opened.get("lines").get(0).get("currency").asString());
        Assertions.assertEquals(List.of("action", "to_status", "actor_type", "amount", "currency", "occurred_at"),
                List.copyOf(opened.get("history").get(0).propertyNames()));

        long caseId = opened.get("id").asLong();
        Long merchantId = jdbcTemplate.queryForObject(
                "SELECT id FROM customers WHERE email = 'merchant.burgerking@laifood.com'", Long.class);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> afterSalesTransitions.fire(
                afterSalesCaseRepository.lockById(caseId),
                new AfterSalesTransitions.Command(AfterSalesTrigger.MERCHANT_OFFER, AfterSalesActor.MERCHANT,
                        merchantId, Money.of("4.89", "USD"), "One portion was there.", null)
        ));
        // Kafka is off here: hand the case's events to the merchant's inbox in order, as its consumer would.
        jdbcTemplate.queryForList("""
                        SELECT payload
                        FROM outbox_events
                        WHERE aggregate_type = 'AFTER_SALES_CASE' AND aggregate_id = ?
                        ORDER BY aggregate_sequence
                        """, String.class, caseId)
                .forEach(payload -> merchantAfterSalesInboxService.handle(
                        eventContracts.read(EventTopic.AFTER_SALES_EVENTS, payload, AfterSalesEventEnvelope.class)));
        JsonNode merchantCase = json(mockMvc.perform(get("/merchant/after-sales")
                        .session(logIn("merchant.burgerking@laifood.com", "merchant123")))
                .andExpect(status().isOk())).get(0);
        Assertions.assertEquals(
                List.of("case_id", "order_id", "order_no", "status", "reason_code", "description", "lines",
                        "requested_amount", "offered_amount", "currency", "merchant_decision", "merchant_note",
                        "deadline_at", "opened_at", "updated_at"),
                List.copyOf(merchantCase.propertyNames())
        );
        Assertions.assertEquals(caseId, merchantCase.get("case_id").asLong());
        Assertions.assertTrue(merchantCase.get("order_no").isString());
        Assertions.assertEquals(4.89, merchantCase.get("offered_amount").asDouble());
        Assertions.assertEquals("USD", merchantCase.get("currency").asString());
        Assertions.assertEquals(List.of("order_item_id", "menu_item_name", "quantity", "unit_price", "amount",
                "currency"), List.copyOf(merchantCase.get("lines").get(0).propertyNames()));
        Assertions.assertEquals("USD", merchantCase.get("lines").get(0).get("currency").asString());
        JsonNode accepted = json(mockMvc.perform(post("/after-sales/cases/{id}/accept-offer", caseId)
                        .session(session)
                        .header("Idempotency-Key", "contract-accept-" + caseId))
                .andExpect(status().isOk()));
        Assertions.assertEquals(
                List.of("id", "order_id", "order_no", "restaurant_id", "kind", "attempt_no", "status", "reason_code",
                        "description", "requested_amount", "offered_amount", "approved_amount", "currency", "lines",
                        "merchant_decision", "merchant_note", "resolution", "refund", "history", "created_at",
                        "updated_at"),
                List.copyOf(accepted.propertyNames())
        );
        Assertions.assertEquals(4.89, accepted.get("offered_amount").asDouble());
        Assertions.assertEquals(4.89, accepted.get("approved_amount").asDouble());
        JsonNode refund = accepted.get("refund");
        Assertions.assertEquals(
                List.of("id", "case_id", "order_id", "order_no", "amount", "currency", "status", "attempts",
                        "next_attempt_at", "created_at"),
                List.copyOf(refund.propertyNames())
        );
        Assertions.assertTrue(refund.get("order_no").isString());
        Assertions.assertEquals("USD", refund.get("currency").asString());
        Assertions.assertEquals(
                List.of("action", "from_status", "to_status", "actor_type", "amount", "currency", "note",
                        "occurred_at"),
                List.copyOf(accepted.get("history").get(1).propertyNames())
        );

        JsonNode listed = json(mockMvc.perform(get("/after-sales/cases").session(session))
                .andExpect(status().isOk())).get("cases").get(0);
        Assertions.assertEquals(caseId, listed.get("id").asLong());
        Assertions.assertFalse(listed.has("history"), "lists carry no history");
        Assertions.assertTrue(listed.has("refund"));

        long refundId = refund.get("id").asLong();
        jdbcTemplate.update("UPDATE refunds SET last_error = 'simulated gateway timeout' WHERE id = ?", refundId);
        JsonNode adminRefunds = json(mockMvc.perform(get("/admin/refunds")
                        .param("status", "PENDING")
                        .param("limit", "100")
                        .session(logIn("demo@laifood.com", "demo123")))
                .andExpect(status().isOk()));
        Assertions.assertTrue(adminRefunds.isArray());
        JsonNode adminRefund = null;
        for (JsonNode candidate : adminRefunds) {
            if (candidate.get("id").asLong() == refundId) {
                adminRefund = candidate;
            }
        }
        Assertions.assertNotNull(adminRefund, adminRefunds::toString);
        Assertions.assertEquals(
                List.of("id", "case_id", "order_id", "order_no", "amount", "currency", "status", "attempts",
                        "last_error", "next_attempt_at", "created_at"),
                List.copyOf(adminRefund.propertyNames())
        );
        Assertions.assertTrue(adminRefund.get("order_no").isString());
        Assertions.assertEquals(4.89, adminRefund.get("amount").asDouble());
        Assertions.assertEquals("USD", adminRefund.get("currency").asString());
        Assertions.assertEquals("simulated gateway timeout", adminRefund.get("last_error").asString());
    }


    /**
     * The merchant's inbox and the rider's deliveries in declaration order, with the order's public number as a string
     * right after order_id (V14: the order events, version 2, carry it to both modules). Null fields are omitted,
     * except in the rider's view, which keeps every other field. Kafka is off here: the order's events go from the
     * outbox to the merchant inbox and to dispatch by hand, as their consumers would hand them.
     */
    @Test
    void merchantAndRiderBodies_shouldCarryTheOrderNumberRightAfterTheOrderId() throws Exception {
        String email = "fulfilment-contract-" + System.nanoTime() + "@example.com";
        signUpAndLogInAs(email);
        Long customerId = jdbcTemplate.queryForObject("SELECT id FROM customers WHERE email = ?", Long.class, email);
        cartService.addMenuItemToCart(customerId, 1L);
        OrderDto order = cartService.checkout(customerId, "PAID");
        String orderNo = String.valueOf(order.orderNo());
        merchantInboxService.handle(latestOrderEvent(order.id(), "order.paid"));
        MockHttpSession merchant = logIn("merchant.burgerking@laifood.com", "merchant123");
        List<String> merchantOrderFields = List.of("order_id", "order_no", "restaurant_id", "customer_id", "status",
                "total_price", "currency", "items", "paid_at", "updated_at");

        JsonNode inboxRow = withField(json(mockMvc.perform(get("/merchant/orders").session(merchant))
                .andExpect(status().isOk())), "order_id", order.id());
        Assertions.assertEquals(merchantOrderFields, List.copyOf(inboxRow.propertyNames()));
        Assertions.assertTrue(inboxRow.get("order_no").isString(), inboxRow.toString());
        Assertions.assertEquals(orderNo, inboxRow.get("order_no").asString());
        JsonNode accepted = json(mockMvc.perform(post("/merchant/orders/{id}/accept", order.id()).session(merchant))
                .andExpect(status().isOk()));
        Assertions.assertEquals(merchantOrderFields, List.copyOf(accepted.propertyNames()));
        Assertions.assertEquals(orderNo, accepted.get("order_no").asString());

        dispatchService.handle(latestOrderEvent(order.id(), "order.accepted"));
        String riderEmail = jdbcTemplate.queryForObject("""
                SELECT customers.email
                FROM deliveries
                JOIN riders ON riders.id = deliveries.rider_id
                JOIN customers ON customers.id = riders.customer_id
                WHERE deliveries.order_id = ?
                """, String.class, order.id());
        JsonNode delivery = withField(json(mockMvc.perform(get("/rider/deliveries")
                        .session(logIn(riderEmail, "rider123")))
                .andExpect(status().isOk())), "order_id", order.id());
        Assertions.assertEquals(
                List.of("delivery_id", "order_id", "order_no", "restaurant_id", "restaurant_name", "status",
                        "assigned_at", "picked_up_at", "delivered_at"),
                List.copyOf(delivery.propertyNames())
        );
        Assertions.assertTrue(delivery.get("order_no").isString(), delivery.toString());
        Assertions.assertEquals(orderNo, delivery.get("order_no").asString());
        Assertions.assertTrue(delivery.get("picked_up_at").isNull(), delivery.toString());
    }


    /**
     * The order event of {@code eventType} the order wrote last, read from the outbox as its consumers read it.
     */
    private OrderEventEnvelope latestOrderEvent(Long orderId, String eventType) {
        String payload = jdbcTemplate.queryForObject("""
                SELECT payload
                FROM outbox_events
                WHERE aggregate_type = 'ORDER' AND aggregate_id = ? AND event_type = ?
                ORDER BY id DESC
                LIMIT 1
                """, String.class, orderId, eventType);
        return eventContracts.read(EventTopic.ORDER_EVENTS, payload, OrderEventEnvelope.class);
    }


    /**
     * The element of the JSON array {@code array} whose {@code field} is {@code value}.
     */
    private static JsonNode withField(JsonNode array, String field, long value) {
        for (JsonNode element : array) {
            if (element.path(field).asLong() == value) {
                return element;
            }
        }
        throw new AssertionError("No element with " + field + " " + value + " in " + array);
    }


    private JsonNode json(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }


    private MockHttpSession signUpAndLogIn(String emailPrefix) throws Exception {
        return signUpAndLogInAs(emailPrefix + System.nanoTime() + "@example.com");
    }


    private MockHttpSession signUpAndLogInAs(String email) throws Exception {
        mockMvc.perform(post("/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "demo123", "first_name": "Money", "last_name": "User"}
                                """.formatted(email)))
                .andExpect(status().isCreated());
        return logIn(email, "demo123");
    }


    private MockHttpSession logIn(String email, String password) throws Exception {
        return (MockHttpSession) mockMvc.perform(post("/login")
                        .param("username", email)
                        .param("password", password))
                .andExpect(status().isOk())
                .andReturn()
                .getRequest()
                .getSession(false);
    }


    private static List<String> keys(String json) {
        Matcher matcher = Pattern.compile("\"([a-z_]+)\":").matcher(json);
        List<String> keys = new ArrayList<>();
        while (matcher.find()) {
            keys.add(matcher.group(1));
        }
        return keys;
    }
}
