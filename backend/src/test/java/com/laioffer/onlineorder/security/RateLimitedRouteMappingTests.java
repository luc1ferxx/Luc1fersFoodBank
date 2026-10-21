package com.laioffer.onlineorder.security;

import com.laioffer.onlineorder.controller.DeadLetterController;
import com.laioffer.onlineorder.error.ApiErrorResponseFactory;
import com.laioffer.onlineorder.error.ApiErrorResponseWriter;
import com.laioffer.onlineorder.observability.ApplicationMetricsService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.ServletRequestPathUtils;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The rate-limit rules match ids as {@code \d+} on the path, so a handler behind a rule must take no other spelling of
 * an id: a path variable converted to a number unconstrained also takes {@code +7}, {@code 0x7}, whitespace and
 * non-ASCII digits, which miss the rule and reach the handler without taking a token. This reads the mapping of every
 * controller method on the classpath (conditional controllers included), finds the ones a rule covers, and checks
 * that their path variables are {@code \d+} and that other spellings do not match them. The list of covered mappings
 * is pinned, so a rule that stops matching its mapping shows up too, and every write mapping must be covered
 * ({@code POST /login} and {@code /logout} are Spring Security's filters, not controller mappings).
 */
class RateLimitedRouteMappingTests {

    private static final Pattern PATH_VARIABLE = Pattern.compile("\\{([^}:]+)(?::([^}]*))?}");

    private static final Set<RequestMethod> ANY_METHOD = EnumSet.of(
            RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE);


    @Test
    void everyRateLimitedMapping_shouldTakeItsIdsAsDecimalDigitsOnly() throws Exception {
        List<String> limited = new ArrayList<>();
        List<String> unlimitedWrites = new ArrayList<>();
        for (RequestMappingInfo mapping : controllerMappings()) {
            Set<RequestMethod> methods = mapping.getMethodsCondition().getMethods();
            for (RequestMethod method : methods.isEmpty() ? ANY_METHOD : methods) {
                for (String pattern : mapping.getPatternValues()) {
                    if (!isRateLimited(method.name(), withIds(pattern, "7"))) {
                        if (method != RequestMethod.GET) {
                            unlimitedWrites.add(method + " " + pattern);
                        }
                        continue;
                    }
                    String route = method + " " + pattern;
                    limited.add(route);
                    Matcher variables = PATH_VARIABLE.matcher(pattern);
                    while (variables.find()) {
                        Assertions.assertEquals("\\d+", variables.group(2), route + ": {" + variables.group(1) + "}");
                    }
                    Assertions.assertTrue(matches(mapping, method.name(), withIds(pattern, "7")), route);
                    if (PATH_VARIABLE.matcher(pattern).find()) {
                        for (String spelling : List.of("+7", "0x7", "%207", "%D9%A7")) {
                            Assertions.assertFalse(
                                    matches(mapping, method.name(), withIds(pattern, spelling)), route + " as " + spelling);
                        }
                    }
                }
            }
        }

        Assertions.assertEquals(List.of(
                "DELETE /cart",
                "DELETE /cart/items/{orderItemId:\\d+}",
                "PATCH /admin/menu-items/{menuItemId:\\d+}",
                "PATCH /admin/merchants/{merchantId:\\d+}",
                "PATCH /admin/restaurants/{restaurantId:\\d+}",
                "PATCH /admin/riders/{riderId:\\d+}",
                "PATCH /orders/{orderId:\\d+}/status",
                "POST /admin/after-sales/{caseId:\\d+}/arbitrate",
                "POST /admin/deliveries/{deliveryId:\\d+}/delivered",
                "POST /admin/deliveries/{deliveryId:\\d+}/picked-up",
                "POST /admin/deliveries/{deliveryId:\\d+}/reassign",
                "POST /admin/merchants",
                "POST /admin/outbox/events/{eventId:\\d+}/abandon",
                "POST /admin/outbox/events/{eventId:\\d+}/retry",
                "POST /admin/refunds/{refundId:\\d+}/retry",
                "POST /admin/riders",
                "POST /after-sales/cases/{caseId:\\d+}/accept-offer",
                "POST /after-sales/cases/{caseId:\\d+}/decline-offer",
                "POST /after-sales/cases/{caseId:\\d+}/escalate",
                "POST /after-sales/cases/{caseId:\\d+}/withdraw",
                "POST /cart",
                "POST /cart/checkout",
                "POST /dead-letters/{deadLetterEventId:\\d+}/replay",
                "POST /merchant/after-sales/{caseId:\\d+}/approve",
                "POST /merchant/after-sales/{caseId:\\d+}/offer",
                "POST /merchant/after-sales/{caseId:\\d+}/reject",
                "POST /merchant/orders/{orderId:\\d+}/accept",
                "POST /merchant/orders/{orderId:\\d+}/preparing",
                "POST /merchant/orders/{orderId:\\d+}/reject",
                "POST /orders/{orderId:\\d+}/after-sales",
                "POST /orders/{orderId:\\d+}/cancel",
                "POST /payments/checkout",
                "POST /rider/deliveries/{deliveryId:\\d+}/delivered",
                "POST /rider/deliveries/{deliveryId:\\d+}/picked-up",
                "POST /signup",
                "PUT /cart/items/{orderItemId:\\d+}",
                "PUT /rider/me/availability"
        ), limited.stream().sorted().toList());
        Assertions.assertEquals(List.of(), unlimitedWrites, "every write endpoint has a rate-limit rule");
    }


    /**
     * Every {@code @Controller} (and so {@code @RestController}) class of the application, read the way Spring MVC
     * reads it, whether or not a condition registers it in a given profile.
     */
    private static List<RequestMappingInfo> controllerMappings() throws Exception {
        MappingReader reader = new MappingReader();
        AnnotationTypeFilter controllers = new AnnotationTypeFilter(Controller.class);
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(MetadataReader metadataReader) throws IOException {
                // The annotation only: the default also evaluates @Conditional and @Profile and skips what they exclude.
                return controllers.match(metadataReader, getMetadataReaderFactory());
            }
        };
        List<RequestMappingInfo> mappings = new ArrayList<>();
        List<Class<?>> types = new ArrayList<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents("com.laioffer.onlineorder")) {
            Class<?> type = ClassUtils.forName(candidate.getBeanClassName(), RateLimitedRouteMappingTests.class.getClassLoader());
            types.add(type);
            for (Method method : ReflectionUtils.getUniqueDeclaredMethods(type, ReflectionUtils.USER_DECLARED_METHODS)) {
                RequestMappingInfo mapping = reader.mappingOf(method, type);
                if (mapping != null) {
                    mappings.add(mapping);
                }
            }
        }
        Assertions.assertTrue(types.contains(DeadLetterController.class), "conditional controllers are read too");
        return mappings;
    }


    private static boolean matches(RequestMappingInfo mapping, String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        ServletRequestPathUtils.parseAndCache(request);
        return mapping.getMatchingCondition(request) != null;
    }


    private static String withIds(String pattern, String id) {
        return PATH_VARIABLE.matcher(pattern).replaceAll(Matcher.quoteReplacement(id));
    }


    private static boolean isRateLimited(String method, String path) throws Exception {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        Mockito.when(redisTemplate.execute(
                Mockito.<RedisScript<Long>>any(),
                Mockito.<List<String>>any(),
                Mockito.any(Object[].class)
        )).thenReturn(1L);
        RateLimitingFilter filter = new RateLimitingFilter(
                true,
                Duration.ofMinutes(2),
                redisTemplate,
                Mockito.mock(ApplicationMetricsService.class),
                new ApiErrorResponseWriter(new ApiErrorResponseFactory(), JsonMapper.builder().build())
        );
        filter.doFilter(new MockHttpServletRequest(method, path), new MockHttpServletResponse(), new MockFilterChain());
        return !Mockito.mockingDetails(redisTemplate).getInvocations().isEmpty();
    }


    /**
     * Spring MVC's own reading of a handler method's annotations, with its default path-pattern parser (the
     * application configures no path matching of its own).
     */
    private static final class MappingReader extends RequestMappingHandlerMapping {

        MappingReader() {
            StaticApplicationContext context = new StaticApplicationContext();
            context.refresh();
            setApplicationContext(context);
            afterPropertiesSet();
        }


        RequestMappingInfo mappingOf(Method method, Class<?> type) {
            return getMappingForMethod(method, type);
        }
    }
}
