package com.laioffer.onlineorder;


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
}
