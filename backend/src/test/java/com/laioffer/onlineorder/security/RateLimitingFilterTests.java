package com.laioffer.onlineorder.security;

import com.laioffer.onlineorder.error.ApiErrorResponseFactory;
import com.laioffer.onlineorder.error.ApiErrorResponseWriter;
import com.laioffer.onlineorder.observability.ApplicationMetricsService;
import jakarta.servlet.ServletOutputStream;
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
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.json.JsonMapper;

import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.security.Principal;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
        Assertions.assertNull(response.getHeader("Retry-After"), "only a refused request carries Retry-After");
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
        Assertions.assertNull(response.getHeader("Retry-After"));
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
        HeaderAtBodyRecordingResponse response = new HeaderAtBodyRecordingResponse("Retry-After");

        filter.doFilter(request, response, new MockFilterChain());

        Assertions.assertEquals(429, response.getStatus());
        Assertions.assertEquals("6", response.getHeader("Retry-After"), "login refills one token every 6s");
        Assertions.assertEquals("6", response.headerWhenTheBodyStarted, "set before the body commits the response");
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


    @Test
    void shouldRateLimitStaffProvisioningWritesByUserButNotReads() throws Exception {
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
                new String[]{"GET", "/admin/merchants"},
                new String[]{"GET", "/admin/riders"},
                new String[]{"POST", "/admin/riders/3"},
                new String[]{"PATCH", "/admin/merchants"},
                new String[]{"PATCH", "/admin/riders/abc"}
        )) {
            filter.doFilter(new MockHttpServletRequest(request[0], request[1]), new MockHttpServletResponse(), new MockFilterChain());
        }
        Mockito.verifyNoInteractions(redisTemplate);

        MockHttpServletRequest create = new MockHttpServletRequest("POST", "/admin/merchants");
        create.setRemoteAddr("10.0.0.5");
        create.setUserPrincipal((Principal) () -> "Admin@Example.com");
        filter.doFilter(create, new MockHttpServletResponse(), new MockFilterChain());

        Assertions.assertEquals("onlineorder:rate-limit:admin-provisioning:user:admin@example.com", capturedRedisKey(redisTemplate));
        Object[] args = capturedRedisArgs(redisTemplate);
        Assertions.assertEquals("10", args[0]);
        Assertions.assertEquals("6000", args[2]);
    }


    @Test
    void shouldRateLimitAfterSalesCommandsByUserButNotReads() throws Exception {
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
                new String[]{"GET", "/admin/after-sales"},
                new String[]{"GET", "/admin/after-sales/9"},
                new String[]{"GET", "/admin/refunds"},
                new String[]{"GET", "/merchant/after-sales"},
                new String[]{"GET", "/admin/after-sales/9/arbitrate"},
                new String[]{"POST", "/admin/after-sales/abc/arbitrate"},
                new String[]{"POST", "/admin/refunds/4"},
                new String[]{"POST", "/merchant/after-sales/5/withdraw"},
                new String[]{"POST", "/merchant/after-sales/5/approve/extra"}
        )) {
            filter.doFilter(new MockHttpServletRequest(request[0], request[1]), new MockHttpServletResponse(), new MockFilterChain());
        }
        Mockito.verifyNoInteractions(redisTemplate);

        for (String[] expected : List.of(
                new String[]{"/admin/after-sales/9/arbitrate", "after-sales-arbitrate", "10", "6000"},
                new String[]{"/admin/refunds/4/retry", "refund-retry", "10", "6000"},
                new String[]{"/merchant/after-sales/5/offer", "merchant-after-sales", "30", "2000"}
        )) {
            Mockito.clearInvocations(redisTemplate);
            MockHttpServletRequest command = new MockHttpServletRequest("POST", expected[0]);
            command.setRemoteAddr("10.0.0.5");
            command.setUserPrincipal((Principal) () -> "Staff@Example.com");
            filter.doFilter(command, new MockHttpServletResponse(), new MockFilterChain());

            Assertions.assertEquals("onlineorder:rate-limit:" + expected[1] + ":user:staff@example.com", capturedRedisKey(redisTemplate));
            Object[] args = capturedRedisArgs(redisTemplate);
            Assertions.assertEquals(expected[2], args[0], expected[0]);
            Assertions.assertEquals(expected[3], args[2], expected[0]);
        }
    }


    @Test
    void shouldRateLimitCustomerAfterSalesCommandsInOneBucketByUserButNotReads() throws Exception {
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
                new String[]{"GET", "/after-sales/cases"},
                new String[]{"GET", "/after-sales/cases/7"},
                new String[]{"GET", "/orders/42/after-sales"},
                new String[]{"GET", "/after-sales/cases/7/withdraw"},
                new String[]{"POST", "/orders/abc/after-sales"},
                new String[]{"POST", "/orders/42/after-sales/extra"},
                new String[]{"POST", "/after-sales/cases"},
                new String[]{"POST", "/after-sales/cases/7"},
                new String[]{"POST", "/after-sales/cases/abc/withdraw"},
                new String[]{"POST", "/after-sales/cases/7/approve"},
                new String[]{"POST", "/after-sales/cases/7/offer"},
                new String[]{"POST", "/after-sales/cases/7/withdrawn"},
                new String[]{"POST", "/after-sales/cases/7/accept-offer/extra"},
                new String[]{"POST", "/orders/42/after-salesx"},
                new String[]{"POST", "/merchant/after-sales/5/withdraw"},
                new String[]{"POST", "/admin/after-sales/9/accept-offer"},
                new String[]{"PUT", "/orders/42/after-sales"},
                new String[]{"DELETE", "/after-sales/cases/7/withdraw"}
        )) {
            filter.doFilter(new MockHttpServletRequest(request[0], request[1]), new MockHttpServletResponse(), new MockFilterChain());
        }
        Mockito.verifyNoInteractions(redisTemplate);

        for (String path : List.of(
                "/orders/42/after-sales",
                "/after-sales/cases/7/accept-offer",
                "/after-sales/cases/7/decline-offer",
                "/after-sales/cases/7/escalate",
                "/after-sales/cases/7/withdraw"
        )) {
            Mockito.clearInvocations(redisTemplate);
            MockHttpServletRequest command = new MockHttpServletRequest("POST", path);
            command.setRemoteAddr("10.0.0.5");
            command.setUserPrincipal((Principal) () -> "Customer@Example.com");
            command.addHeader("Idempotency-Key", "key-" + path);
            filter.doFilter(command, new MockHttpServletResponse(), new MockFilterChain());

            Assertions.assertEquals("onlineorder:rate-limit:customer-after-sales:user:customer@example.com", capturedRedisKey(redisTemplate), path);
            Object[] args = capturedRedisArgs(redisTemplate);
            Assertions.assertEquals("20", args[0], path);
            Assertions.assertEquals("1", args[1], path);
            Assertions.assertEquals("3000", args[2], path);
            Assertions.assertEquals("1", args[3], path);
            Assertions.assertEquals("120", args[5], path);
        }
    }


    @Test
    void shouldKeyCustomerAfterSalesCommandsByTheAuthenticatedLoginAndAnonymousOnesByIp() throws Exception {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        allowRedisRequest(redisTemplate);
        RateLimitingFilter filter = new RateLimitingFilter(
                true,
                Duration.ofMinutes(2),
                redisTemplate,
                Mockito.mock(ApplicationMetricsService.class),
                errorResponseWriter()
        );

        try {
            SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                    "Customer@Example.com", null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
            MockHttpServletRequest signedIn = new MockHttpServletRequest("POST", "/after-sales/cases/7/accept-offer");
            signedIn.setRemoteAddr("10.0.0.5");
            filter.doFilter(signedIn, new MockHttpServletResponse(), new MockFilterChain());
            Assertions.assertEquals("onlineorder:rate-limit:customer-after-sales:user:customer@example.com", capturedRedisKey(redisTemplate));

            Mockito.clearInvocations(redisTemplate);
            SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                    "key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
            MockHttpServletRequest anonymous = new MockHttpServletRequest("POST", "/orders/42/after-sales");
            anonymous.setRemoteAddr("10.0.0.5");
            anonymous.addHeader("X-Forwarded-For", "203.0.113.10");
            filter.doFilter(anonymous, new MockHttpServletResponse(), new MockFilterChain());
            Assertions.assertEquals("onlineorder:rate-limit:customer-after-sales:ip:10.0.0.5", capturedRedisKey(redisTemplate));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }


    /**
     * The filter runs before the idempotency check and never reads the Idempotency-Key, like the checkout and order
     * status rules: a replay with the same key takes a token as well. A request refused with 429 never reaches the
     * handler, so retrying it with its key once the bucket refills is safe: it runs, or, if a request with that key
     * already went through, answers with that key's case.
     */
    @Test
    void shouldRefuseCustomerAfterSalesCommandsOnceTheBucketIsEmptyCountingReplaysOfTheSameKey() throws Exception {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        ApplicationMetricsService metricsService = Mockito.mock(ApplicationMetricsService.class);
        emulateTokenBucketWithoutRefill(redisTemplate);
        RateLimitingFilter filter = new RateLimitingFilter(
                true,
                Duration.ofMinutes(2),
                redisTemplate,
                metricsService,
                errorResponseWriter()
        );

        for (int i = 0; i < 20; i++) {
            MockFilterChain chain = new MockFilterChain();
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(customerCommand("customer@example.com", "/orders/42/after-sales", "open-42"), response, chain);
            Assertions.assertEquals(HttpServletResponse.SC_OK, response.getStatus(), "request " + i);
            Assertions.assertNotNull(chain.getRequest(), "request " + i);
        }

        for (String[] refused : List.of(
                new String[]{"/orders/42/after-sales", "open-42"},
                new String[]{"/orders/43/after-sales", "open-43"},
                new String[]{"/after-sales/cases/7/accept-offer", "accept-7"},
                new String[]{"/after-sales/cases/7/decline-offer", "decline-7"},
                new String[]{"/after-sales/cases/7/escalate", "escalate-7"},
                new String[]{"/after-sales/cases/7/withdraw", "withdraw-7"}
        )) {
            MockFilterChain chain = new MockFilterChain();
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(customerCommand("Customer@Example.com", refused[0], refused[1]), response, chain);
            Assertions.assertEquals(429, response.getStatus(), refused[0]);
            Assertions.assertEquals("3", response.getHeader("Retry-After"), refused[0]);
            Assertions.assertTrue(response.getContentAsString().contains("\"code\":\"RATE_LIMITED\""), refused[0]);
            Assertions.assertNull(chain.getRequest(), refused[0]);
        }
        Mockito.verify(metricsService, Mockito.times(6)).recordRateLimitRejection("customer-after-sales");

        MockFilterChain otherCustomer = new MockFilterChain();
        filter.doFilter(customerCommand("other@example.com", "/orders/42/after-sales", "open-42"), new MockHttpServletResponse(), otherCustomer);
        Assertions.assertNotNull(otherCustomer.getRequest());
    }


    @Test
    void shouldRateLimitOrderCancelCartEditsAndStaffDeliveryCommandsByUserButNotReads() throws Exception {
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
                new String[]{"GET", "/cart"},
                new String[]{"PUT", "/cart"},
                new String[]{"POST", "/cart/items/3"},
                new String[]{"PUT", "/cart/items/abc"},
                new String[]{"GET", "/orders/42/cancel"},
                new String[]{"POST", "/orders/42/cancel/extra"},
                new String[]{"GET", "/merchant/orders"},
                new String[]{"POST", "/merchant/orders/42/delivered"},
                new String[]{"GET", "/rider/deliveries"},
                new String[]{"GET", "/rider/me/availability"},
                new String[]{"POST", "/rider/deliveries/8/reassign"},
                new String[]{"GET", "/admin/deliveries"},
                new String[]{"POST", "/admin/deliveries/8/accept"}
        )) {
            filter.doFilter(new MockHttpServletRequest(request[0], request[1]), new MockHttpServletResponse(), new MockFilterChain());
        }
        Mockito.verifyNoInteractions(redisTemplate);

        for (String[] expected : List.of(
                new String[]{"POST", "/orders/42/cancel", "order-cancel", "20", "3000"},
                new String[]{"POST", "/cart", "cart", "60", "1000"},
                new String[]{"DELETE", "/cart", "cart", "60", "1000"},
                new String[]{"PUT", "/cart/items/3", "cart", "60", "1000"},
                new String[]{"DELETE", "/cart/items/3", "cart", "60", "1000"},
                new String[]{"POST", "/merchant/orders/42/accept", "merchant-orders", "60", "1000"},
                new String[]{"POST", "/merchant/orders/42/reject", "merchant-orders", "60", "1000"},
                new String[]{"POST", "/merchant/orders/42/preparing", "merchant-orders", "60", "1000"},
                new String[]{"POST", "/rider/deliveries/8/picked-up", "rider-deliveries", "30", "2000"},
                new String[]{"POST", "/rider/deliveries/8/delivered", "rider-deliveries", "30", "2000"},
                new String[]{"PUT", "/rider/me/availability", "rider-availability", "10", "6000"},
                new String[]{"POST", "/admin/deliveries/8/picked-up", "admin-deliveries", "60", "1000"},
                new String[]{"POST", "/admin/deliveries/8/delivered", "admin-deliveries", "60", "1000"},
                new String[]{"POST", "/admin/deliveries/8/reassign", "admin-deliveries", "60", "1000"}
        )) {
            String route = expected[0] + " " + expected[1];
            Mockito.clearInvocations(redisTemplate);
            MockHttpServletRequest command = new MockHttpServletRequest(expected[0], expected[1]);
            command.setRemoteAddr("10.0.0.5");
            command.setUserPrincipal((Principal) () -> "Someone@Example.com");
            filter.doFilter(command, new MockHttpServletResponse(), new MockFilterChain());

            Assertions.assertEquals("onlineorder:rate-limit:" + expected[2] + ":user:someone@example.com", capturedRedisKey(redisTemplate), route);
            Object[] args = capturedRedisArgs(redisTemplate);
            Assertions.assertEquals(expected[3], args[0], route);
            Assertions.assertEquals(expected[4], args[2], route);
            Assertions.assertEquals("120", args[5], route);
        }
    }


    /**
     * Like the customer's after-sales commands, a replayed cancel with the same Idempotency-Key takes a token.
     */
    @Test
    void shouldRefuseOrderCancelsOnceTheBucketIsEmptyCountingReplaysOfTheSameKey() throws Exception {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        ApplicationMetricsService metricsService = Mockito.mock(ApplicationMetricsService.class);
        emulateTokenBucketWithoutRefill(redisTemplate);
        RateLimitingFilter filter = new RateLimitingFilter(
                true,
                Duration.ofMinutes(2),
                redisTemplate,
                metricsService,
                errorResponseWriter()
        );

        for (int i = 0; i < 20; i++) {
            MockFilterChain chain = new MockFilterChain();
            filter.doFilter(customerCommand("customer@example.com", "/orders/42/cancel", "cancel-42"), new MockHttpServletResponse(), chain);
            Assertions.assertNotNull(chain.getRequest(), "request " + i);
        }

        MockFilterChain refused = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(customerCommand("customer@example.com", "/orders/43/cancel", "cancel-43"), response, refused);
        Assertions.assertEquals(429, response.getStatus());
        Assertions.assertEquals("3", response.getHeader("Retry-After"));
        Assertions.assertNull(refused.getRequest());
        Mockito.verify(metricsService).recordRateLimitRejection("order-cancel");

        MockFilterChain afterSales = new MockFilterChain();
        filter.doFilter(customerCommand("customer@example.com", "/after-sales/cases/7/withdraw", "withdraw-7"), new MockHttpServletResponse(), afterSales);
        Assertions.assertNotNull(afterSales.getRequest(), "the after-sales bucket is separate");
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
            "POST, /admin/outbox/events/7/%61bandon, outbox-abandon",
            "POST, /admin/after-sales/%39/arbitrate, after-sales-arbitrate",
            "POST, /admin//after-sales/9/arbitrate/, after-sales-arbitrate",
            "POST, /admin/refunds/%34/retry, refund-retry",
            "POST, /merchant/after-sales/5/%61pprove, merchant-after-sales",
            "POST, /merchant/after-sales/5/reject/, merchant-after-sales",
            "POST, /merchant//after-sales/5/offer, merchant-after-sales",
            "POST, /orders/%34%32/after-sales, customer-after-sales",
            "POST, /orders//42/after-sales/, customer-after-sales",
            "POST, /after-sales/cases/%37/%77ithdraw, customer-after-sales",
            "POST, /after-sales//cases/7/accept-offer/, customer-after-sales",
            "POST, /after-sales/cases/7/decline-offer;x=1, customer-after-sales",
            "POST, /after-sales/cases/7/./escalate, customer-after-sales",
            "POST, /after-sales/cases/8/../7/withdraw, customer-after-sales",
            "PATCH, /orders/%34%32/status, order-status",
            "POST, /orders/%34%32/%63ancel, order-cancel",
            "POST, /orders//42/cancel/, order-cancel",
            "POST, /cart/, cart",
            "DELETE, //cart, cart",
            "PUT, /cart/items/%33, cart",
            "DELETE, /cart//items/3/, cart",
            "POST, /merchant/orders/%34%32/accept, merchant-orders",
            "POST, /merchant//orders/42/preparing/, merchant-orders",
            "POST, /rider/deliveries/%38/picked-up, rider-deliveries",
            "PUT, /rider/me/availability/, rider-availability",
            "POST, /admin/deliveries/8/%72eassign, admin-deliveries",
            "POST, /admin//deliveries/8/delivered/, admin-deliveries",
            "POST, /admin/%6Derchants, admin-provisioning",
            "POST, /admin//riders/, admin-provisioning",
            "PATCH, /admin/merchants/%34, admin-staff-status",
            "PATCH, /admin/riders/12/, admin-staff-status"
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
        Assertions.assertEquals("6", response.getHeader("Retry-After"));
        Assertions.assertNull(chain.getRequest());
        Mockito.verify(metricsService).recordRateLimitRejection("login");
    }


    /**
     * Retry-After is the time one request's token takes to come back, ceil(cost x refill period / refill tokens),
     * in whole seconds: every bucket refills fully in 60s, so the smaller the bucket, the longer the wait.
     */
    @ParameterizedTest
    @CsvSource({
            "POST, /login, 6",
            "POST, /signup, 12",
            "POST, /cart/checkout, 3",
            "POST, /payments/checkout, 6",
            "POST, /dead-letters/12/replay, 6",
            "POST, /admin/outbox/events/7/retry, 6",
            "POST, /admin/outbox/events/7/abandon, 6",
            "POST, /admin/after-sales/9/arbitrate, 6",
            "POST, /admin/refunds/4/retry, 6",
            "POST, /merchant/after-sales/5/approve, 2",
            "POST, /orders/42/after-sales, 3",
            "POST, /after-sales/cases/7/withdraw, 3",
            "PATCH, /admin/menu-items/5, 2",
            "POST, /admin/merchants, 6",
            "PATCH, /admin/riders/12, 2",
            "PATCH, /orders/42/status, 2",
            "POST, /orders/42/cancel, 3",
            "POST, /cart, 1",
            "DELETE, /cart/items/3, 1",
            "POST, /merchant/orders/42/accept, 1",
            "POST, /rider/deliveries/8/delivered, 2",
            "PUT, /rider/me/availability, 6",
            "POST, /admin/deliveries/8/reassign, 1"
    })
    void shouldTellARefusedClientHowLongOneTokenTakesToRefill(String method, String uri, String retryAfter) throws Exception {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        Mockito.when(redisTemplate.execute(
                Mockito.<RedisScript<Long>>any(),
                Mockito.<List<String>>any(),
                Mockito.any(Object[].class)
        )).thenReturn(0L);
        RateLimitingFilter filter = new RateLimitingFilter(
                true,
                Duration.ofMinutes(2),
                redisTemplate,
                Mockito.mock(ApplicationMetricsService.class),
                errorResponseWriter()
        );

        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRemoteAddr("10.0.0.5");
        request.setUserPrincipal((Principal) () -> "someone@example.com");
        HeaderAtBodyRecordingResponse response = new HeaderAtBodyRecordingResponse("Retry-After");

        filter.doFilter(request, response, new MockFilterChain());

        Assertions.assertEquals(429, response.getStatus());
        Assertions.assertEquals(retryAfter, response.getHeader("Retry-After"));
        Assertions.assertEquals(retryAfter, response.headerWhenTheBodyStarted);
        Object[] args = capturedRedisArgs(redisTemplate);
        long refillMillis = Long.parseLong(String.valueOf(args[2]));
        long cost = Long.parseLong(String.valueOf(args[3]));
        long refillTokens = Long.parseLong(String.valueOf(args[1]));
        Assertions.assertEquals(
                Long.parseLong(retryAfter),
                Math.max(1L, (long) Math.ceil(cost * refillMillis / (double) refillTokens / 1000d)),
                "matches the rule the script was given"
        );
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


    /**
     * Answers the token-bucket script like Redis would within one instant: every key starts with the rule's capacity
     * and each request takes its cost until none is left.
     */
    private void emulateTokenBucketWithoutRefill(StringRedisTemplate redisTemplate) {
        Map<String, Integer> tokens = new HashMap<>();
        Mockito.when(redisTemplate.execute(
                Mockito.<RedisScript<Long>>any(),
                Mockito.<List<String>>any(),
                Mockito.any(Object[].class)
        )).thenAnswer(invocation -> {
            String key = invocation.<List<String>>getArgument(1).get(0);
            int capacity = Integer.parseInt(invocation.getArgument(2));
            int cost = Integer.parseInt(invocation.getArgument(5));
            int left = tokens.getOrDefault(key, capacity);
            if (left < cost) {
                return 0L;
            }
            tokens.put(key, left - cost);
            return 1L;
        });
    }


    private MockHttpServletRequest customerCommand(String login, String path, String idempotencyKey) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setRemoteAddr("10.0.0.5");
        request.setUserPrincipal((Principal) () -> login);
        request.addHeader("Idempotency-Key", idempotencyKey);
        return request;
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
        return new ApiErrorResponseWriter(new ApiErrorResponseFactory(), JsonMapper.builder().build());
    }


    /**
     * Records a header's value when the body starts: a servlet container drops headers set once the body has
     * committed the response, which {@link MockHttpServletResponse} does not model.
     */
    private static final class HeaderAtBodyRecordingResponse extends MockHttpServletResponse {

        private final String headerName;
        private String headerWhenTheBodyStarted;
        private boolean bodyStarted;


        private HeaderAtBodyRecordingResponse(String headerName) {
            this.headerName = headerName;
        }


        @Override
        public ServletOutputStream getOutputStream() {
            recordHeader();
            return super.getOutputStream();
        }


        @Override
        public PrintWriter getWriter() throws UnsupportedEncodingException {
            recordHeader();
            return super.getWriter();
        }


        private void recordHeader() {
            if (!bodyStarted) {
                bodyStarted = true;
                headerWhenTheBodyStarted = getHeader(headerName);
            }
        }
    }
}
