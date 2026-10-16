package com.laioffer.onlineorder;


import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
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
                "org.springframework.boot.autoconfigure.session.SessionAutoConfiguration," +
                "org.springframework.boot.autoconfigure.session.RedisSessionAutoConfiguration"
})
@AutoConfigureMockMvc
// Spring Boot tests switch tracing propagation off unless asked for; the trace id contract needs W3C extraction.
// Span export stays off (management.otlp.tracing.export.enabled defaults to false).
@AutoConfigureObservability(metrics = false)
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
