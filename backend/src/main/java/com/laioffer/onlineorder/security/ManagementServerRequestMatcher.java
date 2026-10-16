package com.laioffer.onlineorder.security;


import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.WebApplicationContextUtils;


/**
 * Matches requests served by the separate management server, which exists only when management.server.port differs
 * from server.port (MANAGEMENT_SERVER_PORT=8081 in the container image and on Kubernetes).
 *
 * <p>That port serves nothing but the actuator endpoints and is reachable only inside the cluster (it is not part
 * of the Service or the Ingress), so Prometheus and the kubelet's probes can use it without a session. Requests on
 * the application port never match, so /actuator there keeps the ROLE_ADMIN rule. When management runs on the
 * application port (local development, tests) this matcher matches nothing.
 */
public final class ManagementServerRequestMatcher implements RequestMatcher {

    static final String MANAGEMENT_NAMESPACE = "management";


    @Override
    public boolean matches(HttpServletRequest request) {
        WebApplicationContext context =
                WebApplicationContextUtils.getWebApplicationContext(request.getServletContext());
        return context != null && WebServerApplicationContext.hasServerNamespace(context, MANAGEMENT_NAMESPACE);
    }


    @Override
    public String toString() {
        return "ManagementServerRequestMatcher";
    }
}
