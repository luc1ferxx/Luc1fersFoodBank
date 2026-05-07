package com.laioffer.onlineorder;


import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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


    @Test
    void openApiDocs_shouldBePublicAndDescribeApiPaths() throws Exception {
        mockMvc.perform(get("/v3/api-docs")
                        .header("X-Trace-Id", "trace-openapi-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").exists())
                .andExpect(jsonPath("$.paths['/cart']").exists());
    }


    @Test
    void signup_whenRequestBodyIsInvalid_shouldReturnUnifiedValidationErrorWithTraceId() throws Exception {
        mockMvc.perform(post("/signup")
                        .header("X-Trace-Id", "trace-validation-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "",
                                  "password": ""
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("X-Trace-Id", "trace-validation-123"))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("Request validation failed"))
                .andExpect(jsonPath("$.trace_id").value("trace-validation-123"))
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
                        .header("X-Trace-Id", "trace-conflict-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.message").value("Email already exists"))
                .andExpect(jsonPath("$.trace_id").value("trace-conflict-123"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.path").value("/signup"));
    }


    @Test
    void protectedEndpoint_whenUnauthenticated_shouldReturnUnifiedUnauthorizedError() throws Exception {
        mockMvc.perform(get("/cart")
                        .header("X-Trace-Id", "trace-auth-123"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("Authentication required"))
                .andExpect(jsonPath("$.trace_id").value("trace-auth-123"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.path").value("/cart"));
    }
}
