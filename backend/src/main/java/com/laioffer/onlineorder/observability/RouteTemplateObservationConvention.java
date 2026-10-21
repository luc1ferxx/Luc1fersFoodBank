package com.laioffer.onlineorder.observability;


import io.micrometer.common.KeyValue;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.server.observation.DefaultServerRequestObservationConvention;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.stereotype.Component;


import java.util.regex.Pattern;


/**
 * Names server requests by their route without the {@code \d+} constraints that the rate-limited routes put on their
 * ids ({@code /orders/{orderId:\d+}/status} is {@code /orders/{orderId}/status}), so the {@code uri} tag of
 * {@code http.server.requests} and the span names stay the ones the dashboards and saved queries use. Spring Boot's
 * web MVC observation filter takes this bean in place of the default convention.
 */
@Component
public class RouteTemplateObservationConvention extends DefaultServerRequestObservationConvention {

    private static final Pattern CONSTRAINED_VARIABLE = Pattern.compile("\\{([^}:]+):[^}]*}");


    public RouteTemplateObservationConvention(
            @Value("${management.observations.http.server.requests.name:http.server.requests}") String name
    ) {
        super(name);
    }


    @Override
    protected KeyValue uri(ServerRequestObservationContext context) {
        KeyValue uri = super.uri(context);
        return KeyValue.of(uri.getKey(), withoutConstraints(uri.getValue()));
    }


    @Override
    public String getContextualName(ServerRequestObservationContext context) {
        String name = super.getContextualName(context);
        return name == null ? null : withoutConstraints(name);
    }


    static String withoutConstraints(String route) {
        return CONSTRAINED_VARIABLE.matcher(route).replaceAll("{$1}");
    }
}
