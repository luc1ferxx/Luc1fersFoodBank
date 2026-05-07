package com.laioffer.onlineorder.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laioffer.onlineorder.error.ApiErrorResponseFactory;
import com.laioffer.onlineorder.error.ApiErrorResponseWriter;
import com.laioffer.onlineorder.observability.ApplicationMetricsService;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.security.Principal;

class RateLimitingFilterTests {

    @Test
    void shouldRateLimitCheckoutByAuthenticatedUserInsteadOfIpAddress() throws Exception {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
        Mockito.when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        Mockito.when(valueOperations.increment(Mockito.anyString())).thenReturn(1L);

        RateLimitingFilter filter = new RateLimitingFilter(
                true,
                java.time.Duration.ofMinutes(1),
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
        Mockito.verify(valueOperations).increment(Mockito.contains(":user:user@example.com:"));
        Mockito.verify(valueOperations, Mockito.never()).increment(Mockito.contains(":10.0.0.5:"));
    }

    @Test
    void shouldFailOpenWhenRedisRateLimitStoreIsUnavailable() throws Exception {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        ApplicationMetricsService metricsService = Mockito.mock(ApplicationMetricsService.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
        Mockito.when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        Mockito.when(valueOperations.increment(Mockito.anyString())).thenThrow(new RuntimeException("redis unavailable"));

        RateLimitingFilter filter = new RateLimitingFilter(
                true,
                java.time.Duration.ofMinutes(1),
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
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
        Mockito.when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        Mockito.when(valueOperations.increment(Mockito.anyString())).thenReturn(1L);

        RateLimitingFilter filter = new RateLimitingFilter(
                true,
                java.time.Duration.ofMinutes(1),
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
        Mockito.verify(valueOperations).increment(Mockito.contains(":ip:10.0.0.5:"));
        Mockito.verify(valueOperations, Mockito.never()).increment(Mockito.contains("203.0.113.10"));
    }

    @Test
    void shouldReturnUnifiedErrorResponseWhenRateLimitIsExceeded() throws Exception {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        ApplicationMetricsService metricsService = Mockito.mock(ApplicationMetricsService.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
        Mockito.when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        Mockito.when(valueOperations.increment(Mockito.anyString())).thenReturn(11L);

        RateLimitingFilter filter = new RateLimitingFilter(
                true,
                java.time.Duration.ofMinutes(1),
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

    private ApiErrorResponseWriter errorResponseWriter() {
        return new ApiErrorResponseWriter(new ApiErrorResponseFactory(), new ObjectMapper().findAndRegisterModules());
    }
}
