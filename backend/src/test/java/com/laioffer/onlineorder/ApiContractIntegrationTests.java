package com.laioffer.onlineorder;


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


    private MockHttpSession signUpAndLogIn(String emailPrefix) throws Exception {
        String email = emailPrefix + System.nanoTime() + "@example.com";
        mockMvc.perform(post("/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "demo123", "first_name": "Money", "last_name": "User"}
                                """.formatted(email)))
                .andExpect(status().isCreated());
        return (MockHttpSession) mockMvc.perform(post("/login")
                        .param("username", email)
                        .param("password", "demo123"))
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
