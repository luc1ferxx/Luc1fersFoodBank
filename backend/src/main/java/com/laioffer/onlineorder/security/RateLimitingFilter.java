package com.laioffer.onlineorder.security;


import com.laioffer.onlineorder.error.ApiErrorResponseWriter;
import com.laioffer.onlineorder.observability.ApplicationMetricsService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;


import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;


@Component
public class RateLimitingFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(RateLimitingFilter.class);
    private static final RedisScript<Long> TOKEN_BUCKET_SCRIPT = new DefaultRedisScript<>("""
            local key = KEYS[1]
            local capacity = tonumber(ARGV[1])
            local refill_tokens = tonumber(ARGV[2])
            local refill_period_millis = tonumber(ARGV[3])
            local cost = tonumber(ARGV[4])
            local now_millis = tonumber(ARGV[5])
            local ttl_seconds = tonumber(ARGV[6])

            local bucket = redis.call('HMGET', key, 'tokens', 'updated_at_ms')
            local tokens = tonumber(bucket[1])
            local updated_at = tonumber(bucket[2])

            if tokens == nil then
                tokens = capacity
            end
            if updated_at == nil then
                updated_at = now_millis
            end

            local elapsed = math.max(0, now_millis - updated_at)
            if refill_period_millis > 0 and refill_tokens > 0 and elapsed > 0 then
                local refill = (elapsed / refill_period_millis) * refill_tokens
                tokens = math.min(capacity, tokens + refill)
            end

            local allowed = 0
            if tokens >= cost then
                tokens = tokens - cost
                allowed = 1
            end

            redis.call('HSET', key, 'tokens', tokens, 'updated_at_ms', now_millis)
            redis.call('EXPIRE', key, ttl_seconds)
            return allowed
            """, Long.class);

    private final boolean enabled;
    private final Duration bucketTtl;
    private final StringRedisTemplate redisTemplate;
    private final ApplicationMetricsService metricsService;
    private final ApiErrorResponseWriter apiErrorResponseWriter;


    public RateLimitingFilter(
            @Value("${app.security.rate-limit.enabled:true}") boolean enabled,
            @Value("${app.security.rate-limit.bucket-ttl:${app.security.rate-limit.window:2m}}") Duration bucketTtl,
            StringRedisTemplate redisTemplate,
            ApplicationMetricsService metricsService,
            ApiErrorResponseWriter apiErrorResponseWriter
    ) {
        this.enabled = enabled;
        this.bucketTtl = bucketTtl;
        this.redisTemplate = redisTemplate;
        this.metricsService = metricsService;
        this.apiErrorResponseWriter = apiErrorResponseWriter;
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
            metricsService.recordRateLimitRejection(rule.routeKey());
            apiErrorResponseWriter.write(
                    request,
                    response,
                    HttpStatus.TOO_MANY_REQUESTS,
                    "RATE_LIMITED",
                    "Too many requests"
            );
            return;
        }

        filterChain.doFilter(request, response);
    }


    private boolean allow(HttpServletRequest request, RateLimitRule rule) {
        Instant now = Instant.now();
        String redisKey = "onlineorder:rate-limit:" + rule.routeKey() + ":" + resolveRateLimitSubject(request, rule);

        try {
            Long allowed = redisTemplate.execute(
                    TOKEN_BUCKET_SCRIPT,
                    List.of(redisKey),
                    String.valueOf(rule.capacity()),
                    String.valueOf(rule.refillTokens()),
                    String.valueOf(Math.max(rule.refillPeriod().toMillis(), 1L)),
                    String.valueOf(rule.cost()),
                    String.valueOf(now.toEpochMilli()),
                    String.valueOf(resolveTtlSeconds(rule))
            );
            if (allowed == null) {
                metricsService.recordRateLimitStoreFailure(rule.routeKey());
                return true;
            }
            return allowed == 1L;
        } catch (RuntimeException ex) {
            logger.warn("Rate-limit store unavailable for route {} and key {}. Failing open.", rule.routeKey(), redisKey, ex);
            metricsService.recordRateLimitStoreFailure(rule.routeKey());
            return true;
        }
    }


    /**
     * The rules match ids as {@code \d+}, so the mappings behind them take {@code {id:\d+}}: an unconstrained numeric
     * path variable also converts {@code +42}, {@code 0x2A}, whitespace and non-ASCII digits, which would reach the
     * handler past the rule. {@code RateLimitedRouteMappingTests} checks every mapping a rule covers.
     */
    private RateLimitRule resolveRule(HttpServletRequest request) {
        String method = request.getMethod();
        String path = resolveRequestPath(request);
        if (HttpMethod.POST.matches(method) && "/login".equals(path)) {
            return new RateLimitRule("login", 10, 1, Duration.ofSeconds(6), 1, true);
        }
        if (HttpMethod.POST.matches(method) && "/signup".equals(path)) {
            return new RateLimitRule("signup", 5, 1, Duration.ofSeconds(12), 1, true);
        }
        if (HttpMethod.POST.matches(method) && "/cart/checkout".equals(path)) {
            return new RateLimitRule("cart-checkout", 20, 1, Duration.ofSeconds(3), 1, false);
        }
        if (HttpMethod.POST.matches(method) && "/payments/checkout".equals(path)) {
            return new RateLimitRule("payment-checkout", 10, 1, Duration.ofSeconds(6), 1, false);
        }
        if (HttpMethod.POST.matches(method) && path.matches("^/dead-letters/\\d+/replay$")) {
            return new RateLimitRule("dead-letter-replay", 10, 1, Duration.ofSeconds(6), 1, false);
        }
        if (HttpMethod.POST.matches(method) && path.matches("^/admin/outbox/events/\\d+/retry$")) {
            return new RateLimitRule("outbox-retry", 10, 1, Duration.ofSeconds(6), 1, false);
        }
        if (HttpMethod.POST.matches(method) && path.matches("^/admin/outbox/events/\\d+/abandon$")) {
            return new RateLimitRule("outbox-abandon", 10, 1, Duration.ofSeconds(6), 1, false);
        }
        if (HttpMethod.POST.matches(method) && path.matches("^/admin/after-sales/\\d+/arbitrate$")) {
            return new RateLimitRule("after-sales-arbitrate", 10, 1, Duration.ofSeconds(6), 1, false);
        }
        if (HttpMethod.POST.matches(method) && path.matches("^/admin/refunds/\\d+/retry$")) {
            return new RateLimitRule("refund-retry", 10, 1, Duration.ofSeconds(6), 1, false);
        }
        if (HttpMethod.POST.matches(method) && path.matches("^/merchant/after-sales/\\d+/(approve|reject|offer)$")) {
            return new RateLimitRule("merchant-after-sales", 30, 1, Duration.ofSeconds(2), 1, false);
        }
        if (HttpMethod.POST.matches(method)
                && (path.matches("^/orders/\\d+/after-sales$")
                || path.matches("^/after-sales/cases/\\d+/(accept-offer|decline-offer|escalate|withdraw)$"))) {
            return new RateLimitRule("customer-after-sales", 20, 1, Duration.ofSeconds(3), 1, false);
        }
        if (HttpMethod.PATCH.matches(method) && path.matches("^/admin/(menu-items|restaurants)/\\d+$")) {
            return new RateLimitRule("admin-catalog", 30, 1, Duration.ofSeconds(2), 1, false);
        }
        if (HttpMethod.POST.matches(method) && path.matches("^/admin/(merchants|riders)$")) {
            return new RateLimitRule("admin-provisioning", 10, 1, Duration.ofSeconds(6), 1, false);
        }
        if (HttpMethod.PATCH.matches(method) && path.matches("^/admin/(merchants|riders)/\\d+$")) {
            return new RateLimitRule("admin-staff-status", 30, 1, Duration.ofSeconds(2), 1, false);
        }
        if (HttpMethod.PATCH.matches(method) && path.matches("^/orders/\\d+/status$")) {
            return new RateLimitRule("order-status", 30, 1, Duration.ofSeconds(2), 1, false);
        }
        return null;
    }


    /**
     * Matches the path the handlers see rather than the raw request URI, so that
     * percent-encoded ({@code /%6Cogin}), path-parameter ({@code /login;x=1}),
     * duplicate-slash or dot-segment variants cannot bypass a rule.
     */
    static String resolveRequestPath(HttpServletRequest request) {
        String path;
        try {
            path = UrlPathHelper.defaultInstance.getPathWithinApplication(request);
        } catch (IllegalArgumentException ex) {
            path = request.getRequestURI();
        }
        path = StringUtils.cleanPath(path).replaceAll("/{2,}", "/");
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }


    private long resolveTtlSeconds(RateLimitRule rule) {
        long fullRefillMillis = Math.max(1L, rule.refillPeriod().toMillis()) * rule.capacity() / Math.max(1, rule.refillTokens());
        long ruleTtlSeconds = Duration.ofMillis(fullRefillMillis).plus(rule.refillPeriod()).toSeconds();
        return Math.max(Math.max(bucketTtl.toSeconds(), ruleTtlSeconds), 1L);
    }


    private String extractClientIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }


    private String resolveRateLimitSubject(HttpServletRequest request, RateLimitRule rule) {
        if (rule.ipBound()) {
            return "ip:" + extractClientIp(request);
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken)
                && authentication.getName() != null
                && !authentication.getName().isBlank()) {
            return "user:" + authentication.getName().trim().toLowerCase();
        }

        if (request.getUserPrincipal() != null && request.getUserPrincipal().getName() != null && !request.getUserPrincipal().getName().isBlank()) {
            return "user:" + request.getUserPrincipal().getName().trim().toLowerCase();
        }

        return "ip:" + extractClientIp(request);
    }


    private record RateLimitRule(
            String routeKey,
            int capacity,
            int refillTokens,
            Duration refillPeriod,
            int cost,
            boolean ipBound
    ) {
    }
}
