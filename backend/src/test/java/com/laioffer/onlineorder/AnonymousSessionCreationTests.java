package com.laioffer.onlineorder;


import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;


import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;


/**
 * Spring Session is excluded so the servlet container's own session is used: any code path that calls
 * {@code request.getSession(true)} for an anonymous request leaves a {@link MockHttpSession} on the request,
 * which in production would be a session written to Redis. MockMvc never writes a session cookie, so the
 * no-Set-Cookie checks live in {@link AnonymousSessionStoreTests}, which runs the real Spring Session filter.
 */
@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.session.autoconfigure.SessionAutoConfiguration," +
                "org.springframework.boot.session.data.redis.autoconfigure.SessionDataRedisAutoConfiguration"
})
@AutoConfigureMockMvc
@ActiveProfiles("h2")
class AnonymousSessionCreationTests {

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.cache.type", () -> "none");
        registry.add("app.kafka.enabled", () -> "false");
        registry.add("app.outbox.publisher-enabled", () -> "false");
        registry.add("app.cleanup.enabled", () -> "false");
        registry.add("app.security.rate-limit.enabled", () -> "false");
    }

    @Autowired
    private MockMvc mockMvc;


    @ParameterizedTest
    @ValueSource(strings = {"/cart", "/orders", "/me", "/notifications", "/actuator/metrics", "/unknown-spa-route"})
    void anonymousGet_toProtectedPath_shouldReturnUnauthorizedWithoutCreatingSession(String path) throws Exception {
        MvcResult result = mockMvc.perform(get(path))
                .andExpect(status().isUnauthorized())
                .andReturn();

        assertNoSessionCreated(result);
    }


    @Test
    void anonymousPost_toProtectedEndpoint_shouldReturnUnauthorizedWithoutCreatingSession() throws Exception {
        MvcResult result = mockMvc.perform(post("/cart")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"menu_id\":1}"))
                .andExpect(status().isUnauthorized())
                .andReturn();

        assertNoSessionCreated(result);
    }


    @Test
    void anonymousGet_toPublicEndpoint_shouldNotCreateSession() throws Exception {
        MvcResult result = mockMvc.perform(get("/restaurants/menu"))
                .andExpect(status().isOk())
                .andReturn();

        assertNoSessionCreated(result);
    }


    @Test
    void failedLogin_shouldReturnUnauthorizedWithoutCreatingSession() throws Exception {
        MvcResult result = mockMvc.perform(post("/login")
                        .param("username", "missing-" + System.nanoTime() + "@example.com")
                        .param("password", "wrong-password"))
                .andExpect(status().isUnauthorized())
                .andReturn();

        assertNoSessionCreated(result);
    }


    @Test
    void anonymousLogout_shouldNotCreateSession() throws Exception {
        MvcResult result = mockMvc.perform(post("/logout"))
                .andExpect(status().isOk())
                .andReturn();

        assertNoSessionCreated(result);
    }


    @Test
    void successfulLogin_shouldCreateSessionThatAuthorizesTheNextRequest() throws Exception {
        MvcResult loginResult = mockMvc.perform(post("/login")
                        .param("username", "demo@laifood.com")
                        .param("password", "demo123"))
                .andExpect(status().isOk())
                .andReturn();

        HttpSession session = loginResult.getRequest().getSession(false);
        Assertions.assertNotNull(session);
        Assertions.assertNotNull(session.getAttribute("SPRING_SECURITY_CONTEXT"));
        Assertions.assertNull(session.getAttribute("SPRING_SECURITY_SAVED_REQUEST"));

        mockMvc.perform(get("/cart").session((MockHttpSession) session))
                .andExpect(status().isOk());
    }


    private void assertNoSessionCreated(MvcResult result) {
        Assertions.assertNull(result.getRequest().getSession(false));
    }
}
