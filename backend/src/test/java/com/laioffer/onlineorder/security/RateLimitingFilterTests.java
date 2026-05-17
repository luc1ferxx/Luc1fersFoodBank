package com.laioffer.onlineorder.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laioffer.onlineorder.error.ApiErrorResponseFactory;
import com.laioffer.onlineorder.error.ApiErrorResponseWriter;
import com.laioffer.onlineorder.observability.ApplicationMetricsService;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
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
