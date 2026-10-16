package com.laioffer.onlineorder.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laioffer.onlineorder.error.ApiErrorResponseFactory;
import com.laioffer.onlineorder.error.ApiErrorResponseWriter;
import com.laioffer.onlineorder.observability.ApplicationMetricsService;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.security.Principal;
import java.time.Duration;
import java.util.List;

class RateLimitingFilterTests {

    @Test
    void shouldRateLimitCheckoutByAuthenticatedUserInsteadOfIpAddress() throws Exception {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        allowRedisRequest(redisTemplate);

        RateLimitingFilter filter = new RateLimitingFilter(
                true,
                Duration.ofMinutes(2),
                redisTemplate,
                Mockito.mock(ApplicationMetricsService.class),
                errorResponseWriter()
        );

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/payments/checkout");
        request.setRemoteAddr("10.0.0.5");
        request.setUserPrincipal((Principal) () -> "user@example.com");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        Assertions.assertEquals(HttpServletResponse.SC_OK, response.getStatus());
        Assertions.assertEquals(
                "onlineorder:rate-limit:payment-checkout:user:user@example.com",
                capturedRedisKey(redisTemplate)
        );
    }

    @Test
    void shouldFailOpenWhenRedisRateLimitStoreIsUnavailable() throws Exception {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        ApplicationMetricsService metricsService = Mockito.mock(ApplicationMetricsService.class);
        Mockito.when(redisTemplate.execute(
                Mockito.<RedisScript<Long>>any(),
                Mockito.<List<String>>any(),
                Mockito.any(Object[].class)
        )).thenThrow(new RuntimeException("redis unavailable"));

        RateLimitingFilter filter = new RateLimitingFilter(
                true,
                Duration.ofMinutes(2),
                redisTemplate,
                metricsService,
                errorResponseWriter()
        );

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/login");
        request.setRemoteAddr("10.0.0.5");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        Assertions.assertEquals(HttpServletResponse.SC_OK, response.getStatus());
        Mockito.verify(metricsService).recordRateLimitStoreFailure("login");
    }

    @Test
    void shouldKeepAnonymousLoginRateLimitBoundToRemoteIpAddress() throws Exception {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        allowRedisRequest(redisTemplate);

        RateLimitingFilter filter = new RateLimitingFilter(
                true,
                Duration.ofMinutes(2),
                redisTemplate,
                Mockito.mock(ApplicationMetricsService.class),
                errorResponseWriter()
        );

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/login");
        request.setRemoteAddr("10.0.0.5");
        request.addHeader("X-Forwarded-For", "203.0.113.10");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        Assertions.assertEquals(HttpServletResponse.SC_OK, response.getStatus());
        Assertions.assertEquals(
                "onlineorder:rate-limit:login:ip:10.0.0.5",
                capturedRedisKey(redisTemplate)
        );
    }

    @Test
    void shouldReturnUnifiedErrorResponseWhenRateLimitIsExceeded() throws Exception {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        ApplicationMetricsService metricsService = Mockito.mock(ApplicationMetricsService.class);
        Mockito.when(redisTemplate.execute(
                Mockito.<RedisScript<Long>>any(),
                Mockito.<List<String>>any(),
                Mockito.any(Object[].class)
        )).thenReturn(0L);

        RateLimitingFilter filter = new RateLimitingFilter(
                true,
                Duration.ofMinutes(2),
                redisTemplate,
                metricsService,
                errorResponseWriter()
        );

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/login");
        request.setRemoteAddr("10.0.0.5");
        request.addHeader("X-Trace-Id", "trace-rate-12345");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        Assertions.assertEquals(429, response.getStatus());
        Assertions.assertEquals("application/json", response.getContentType());
        Assertions.assertTrue(response.getContentAsString().contains("\"code\":\"RATE_LIMITED\""));
        Assertions.assertTrue(response.getContentAsString().contains("\"trace_id\":\"trace-rate-12345\""));
        Mockito.verify(metricsService).recordRateLimitRejection("login");
    }


    @Test
    void shouldPassTokenBucketParametersToRedisScript() throws Exception {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        allowRedisRequest(redisTemplate);

        RateLimitingFilter filter = new RateLimitingFilter(
                true,
                Duration.ofMinutes(2),
                redisTemplate,
                Mockito.mock(ApplicationMetricsService.class),
                errorResponseWriter()
        );

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/signup");
        request.setRemoteAddr("10.0.0.5");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        Object[] args = capturedRedisArgs(redisTemplate);
        Assertions.assertEquals("5", args[0]);
        Assertions.assertEquals("1", args[1]);
        Assertions.assertEquals("12000", args[2]);
        Assertions.assertEquals("1", args[3]);
        Assertions.assertTrue(Long.parseLong(String.valueOf(args[4])) > 0);
        Assertions.assertEquals("120", args[5]);
    }


    @Test
    void shouldRateLimitAdminCatalogUpdatesByUserButNotOtherMethodsOrPaths() throws Exception {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        allowRedisRequest(redisTemplate);
        RateLimitingFilter filter = new RateLimitingFilter(
                true,
                Duration.ofMinutes(2),
                redisTemplate,
                Mockito.mock(ApplicationMetricsService.class),
                errorResponseWriter()
        );

        for (String[] request : List.of(
                new String[]{"GET", "/admin/menu-items/5"},
                new String[]{"PATCH", "/admin/menu-items/abc"},
                new String[]{"PATCH", "/admin/restaurants/3/menu"}
        )) {
            filter.doFilter(new MockHttpServletRequest(request[0], request[1]), new MockHttpServletResponse(), new MockFilterChain());
        }
        Mockito.verifyNoInteractions(redisTemplate);

        MockHttpServletRequest update = new MockHttpServletRequest("PATCH", "/admin/restaurants/3");
        update.setRemoteAddr("10.0.0.5");
        update.setUserPrincipal((Principal) () -> "Admin@Example.com");
        filter.doFilter(update, new MockHttpServletResponse(), new MockFilterChain());

        Assertions.assertEquals("onlineorder:rate-limit:admin-catalog:user:admin@example.com", capturedRedisKey(redisTemplate));
        Object[] args = capturedRedisArgs(redisTemplate);
        Assertions.assertEquals("30", args[0]);
        Assertions.assertEquals("2000", args[2]);
    }


    @ParameterizedTest
    @CsvSource({
            "POST, /%6Cogin, login",
            "POST, /%6C%6F%67%69%6E, login",
            "POST, /login;jsessionid=abc, login",
            "POST, //login, login",
            "POST, /login/, login",
            "POST, /./login, login",
            "POST, /%73ignup, signup",
            "POST, /cart/%63heckout, cart-checkout",
            "POST, /cart//checkout, cart-checkout",
            "POST, /payments/%63heckout, payment-checkout",
            "PATCH, /admin/menu-items/%35, admin-catalog",
            "PATCH, /admin//restaurants/3/, admin-catalog",
            "POST, /dead-letters/%31%32/replay, dead-letter-replay",
            "POST, /admin/outbox/events/7/%72etry, outbox-retry",
            "PATCH, /orders/%34%32/status, order-status"
    })
    void shouldApplyRateLimitRuleToEncodedAndNonNormalizedPaths(String method, String uri, String routeKey) throws Exception {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        allowRedisRequest(redisTemplate);

        RateLimitingFilter filter = new RateLimitingFilter(
                true,
                Duration.ofMinutes(2),
                redisTemplate,
                Mockito.mock(ApplicationMetricsService.class),
                errorResponseWriter()
        );

        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRemoteAddr("10.0.0.5");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        Assertions.assertEquals(
                "onlineorder:rate-limit:" + routeKey + ":ip:10.0.0.5",
                capturedRedisKey(redisTemplate)
        );
    }


    @Test
    void shouldRejectPercentEncodedLoginWhenBucketIsEmpty() throws Exception {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        ApplicationMetricsService metricsService = Mockito.mock(ApplicationMetricsService.class);
        Mockito.when(redisTemplate.execute(
                Mockito.<RedisScript<Long>>any(),
                Mockito.<List<String>>any(),
                Mockito.any(Object[].class)
        )).thenReturn(0L);

        RateLimitingFilter filter = new RateLimitingFilter(
                true,
                Duration.ofMinutes(2),
                redisTemplate,
                metricsService,
                errorResponseWriter()
        );

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/%6Cogin");
        request.setRemoteAddr("10.0.0.5");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        Assertions.assertEquals(429, response.getStatus());
        Assertions.assertNull(chain.getRequest());
        Mockito.verify(metricsService).recordRateLimitRejection("login");
    }


    @Test
    void shouldMatchRulesRelativeToTheServletContextPath() throws Exception {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        allowRedisRequest(redisTemplate);

        RateLimitingFilter filter = new RateLimitingFilter(
                true,
                Duration.ofMinutes(2),
                redisTemplate,
                Mockito.mock(ApplicationMetricsService.class),
                errorResponseWriter()
        );

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/app/%6Cogin");
        request.setContextPath("/app");
        request.setRemoteAddr("10.0.0.5");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        Assertions.assertEquals("onlineorder:rate-limit:login:ip:10.0.0.5", capturedRedisKey(redisTemplate));
    }


    @Test
    void shouldNotRateLimitPathsThatOnlyResembleLimitedRoutes() throws Exception {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);

        RateLimitingFilter filter = new RateLimitingFilter(
                true,
                Duration.ofMinutes(2),
                redisTemplate,
                Mockito.mock(ApplicationMetricsService.class),
                errorResponseWriter()
        );

        for (String uri : List.of("/loginx", "/login/extra", "/%6Cogins", "/dead-letters/abc/replay")) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(new MockHttpServletRequest("POST", uri), response, new MockFilterChain());
            Assertions.assertEquals(HttpServletResponse.SC_OK, response.getStatus(), uri);
        }
        Mockito.verifyNoInteractions(redisTemplate);
    }


    private void allowRedisRequest(StringRedisTemplate redisTemplate) {
        Mockito.when(redisTemplate.execute(
                Mockito.<RedisScript<Long>>any(),
                Mockito.<List<String>>any(),
                Mockito.any(Object[].class)
        )).thenReturn(1L);
    }


    private String capturedRedisKey(StringRedisTemplate redisTemplate) {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        Mockito.verify(redisTemplate).execute(
                Mockito.<RedisScript<Long>>any(),
                keysCaptor.capture(),
                Mockito.any(Object[].class)
        );
        return keysCaptor.getValue().get(0);
    }


    private Object[] capturedRedisArgs(StringRedisTemplate redisTemplate) {
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        Mockito.verify(redisTemplate).execute(
                Mockito.<RedisScript<Long>>any(),
                Mockito.<List<String>>any(),
                argsCaptor.capture()
        );
        return argsCaptor.getValue();
    }

    private ApiErrorResponseWriter errorResponseWriter() {
        return new ApiErrorResponseWriter(new ApiErrorResponseFactory(), new ObjectMapper().findAndRegisterModules());
    }
}
