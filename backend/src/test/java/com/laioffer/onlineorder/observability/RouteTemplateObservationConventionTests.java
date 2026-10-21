package com.laioffer.onlineorder.observability;


import io.micrometer.common.KeyValue;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;


class RouteTemplateObservationConventionTests {

    private final RouteTemplateObservationConvention convention =
            new RouteTemplateObservationConvention("http.server.requests");


    @Test
    void shouldNameARouteByItsTemplateWithoutTheIdConstraints() {
        ServerRequestObservationContext context = context("PATCH", "/orders/7/status", "/orders/{orderId:\\d+}/status");

        Assertions.assertEquals("/orders/{orderId}/status", uriOf(context));
        Assertions.assertEquals("http patch /orders/{orderId}/status", convention.getContextualName(context));
        Assertions.assertEquals("http.server.requests", convention.getName());
    }


    @Test
    void shouldLeaveUnconstrainedRoutesAndUnmatchedRequestsAsTheDefaultConventionNamesThem() {
        Assertions.assertEquals(
                "/restaurant/{restaurantId}/menu", uriOf(context("GET", "/restaurant/3/menu", "/restaurant/{restaurantId}/menu")));
        Assertions.assertEquals(
                "/admin/deliveries/{deliveryId}/{a}", uriOf(context("POST", "/admin/deliveries/8/x", "/admin/deliveries/{deliveryId:\\d+}/{a}")));

        ServerRequestObservationContext unmatched = context("GET", "/nowhere", null);
        Assertions.assertEquals("UNKNOWN", uriOf(unmatched));
        Assertions.assertEquals("http get", convention.getContextualName(unmatched));
    }


    private static ServerRequestObservationContext context(String method, String path, String pattern) {
        ServerRequestObservationContext context =
                new ServerRequestObservationContext(new MockHttpServletRequest(method, path), new MockHttpServletResponse());
        context.setPathPattern(pattern);
        return context;
    }


    private String uriOf(ServerRequestObservationContext context) {
        return convention.getLowCardinalityKeyValues(context).stream()
                .filter(keyValue -> keyValue.getKey().equals("uri"))
                .map(KeyValue::getValue)
                .findFirst()
                .orElseThrow();
    }
}
