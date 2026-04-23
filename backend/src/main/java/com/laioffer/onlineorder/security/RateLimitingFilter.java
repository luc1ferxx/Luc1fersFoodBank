package com.laioffer.onlineorder.security;


import com.laioffer.onlineorder.observability.ApplicationMetricsService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;


import java.io.IOException;
import java.time.Duration;
import java.time.Instant;


@Component
public class RateLimitingFilter extends OncePerRequestFilter {

    private final boolean enabled;
    private final Duration window;
    private final StringRedisTemplate redisTemplate;
    private final ApplicationMetricsService metricsService;


    public RateLimitingFilter(
            @Value("${app.security.rate-limit.enabled:true}") boolean enabled,
            @Value("${app.security.rate-limit.window:1m}") Duration window,
            StringRedisTemplate redisTemplate,
            ApplicationMetricsService metricsService
    ) {
        this.enabled = enabled;
        this.window = window;
        this.redisTemplate = redisTemplate;
        this.metricsService = metricsService;
    }


    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (!enabled) {
            filterChain.doFilter(request, response);
            return;
        }

        RateLimitRule rule = resolveRule(request);
        if (rule == null) {
            filterChain.doFilter(request, response);
            return;
        }

        if (!allow(request, rule)) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType("application/json");
            response.getWriter().write("{\"message\":\"Too many requests\"}");
            metricsService.recordRateLimitRejection(rule.routeKey());
            return;
        }

        filterChain.doFilter(request, response);
    }


    private boolean allow(HttpServletRequest request, RateLimitRule rule) {
        Instant now = Instant.now();
        String clientIp = extractClientIp(request);
        long windowMillis = Math.max(window.toMillis(), 1000L);
        long windowBucket = now.toEpochMilli() / windowMillis;
        String redisKey = "onlineorder:rate-limit:" + rule.routeKey() + ":" + clientIp + ":" + windowBucket;

        Long count = redisTemplate.opsForValue().increment(redisKey);
        if (count != null && count == 1L) {
            redisTemplate.expire(redisKey, window.plusSeconds(1));
        }

        return count == null || count <= rule.limit();
    }


    private RateLimitRule resolveRule(HttpServletRequest request) {
        String method = request.getMethod();
        String path = request.getRequestURI();
        if (HttpMethod.POST.matches(method) && "/login".equals(path)) {
            return new RateLimitRule("login", 10);
        }
        if (HttpMethod.POST.matches(method) && "/signup".equals(path)) {
            return new RateLimitRule("signup", 5);
        }
        if (HttpMethod.POST.matches(method) && "/cart/checkout".equals(path)) {
            return new RateLimitRule("cart-checkout", 20);
        }
        if (HttpMethod.POST.matches(method) && "/payments/checkout".equals(path)) {
            return new RateLimitRule("payment-checkout", 20);
        }
        if (HttpMethod.POST.matches(method) && path.matches("^/dead-letters/\\d+/replay$")) {
            return new RateLimitRule("dead-letter-replay", 10);
        }
        if (HttpMethod.PATCH.matches(method) && path.matches("^/orders/\\d+/status$")) {
            return new RateLimitRule("order-status", 30);
        }
        return null;
    }


    private String extractClientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }


    private record RateLimitRule(String routeKey, int limit) {
    }
}
