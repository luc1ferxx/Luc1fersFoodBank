package com.laioffer.onlineorder;


import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;


import java.util.Map;


/**
 * The deployment layout: MANAGEMENT_SERVER_PORT moves the actuator to its own port, where Prometheus and the
 * Kubernetes probes reach it without a session, while the application port keeps serving the API and no longer
 * exposes /actuator.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "management.server.port=0",
                "app.security.rate-limit.enabled=false",
                "spring.autoconfigure.exclude=org.springframework.boot.session.autoconfigure.SessionAutoConfiguration," +
                        "org.springframework.boot.session.data.redis.autoconfigure.SessionDataRedisAutoConfiguration",
                "spring.cache.type=none",
                // No Redis in this test; the readiness group would otherwise report DOWN.
                "management.endpoint.health.group.readiness.include=readinessState,db"
        }
)
// Spring Boot tests disable metrics export, and with it the Prometheus endpoint, unless asked for.
@AutoConfigureMetrics
@ActiveProfiles("h2")
@AutoConfigureTestRestTemplate
class ManagementPortIntegrationTests {

    @LocalServerPort
    private int serverPort;

    @LocalManagementPort
    private int managementPort;

    @Autowired
    private TestRestTemplate restTemplate;


    @Test
    void managementPort_shouldServePrometheusAndProbesWithoutSession() {
        Assertions.assertNotEquals(serverPort, managementPort);

        ResponseEntity<String> scrape = restTemplate.getForEntity(management("/actuator/prometheus"), String.class);
        Assertions.assertEquals(HttpStatus.OK, scrape.getStatusCode());
        Assertions.assertNotNull(scrape.getBody());
        Assertions.assertTrue(scrape.getBody().contains("onlineorder_outbox_oldest_pending_age_seconds"));
        Assertions.assertTrue(scrape.getBody().contains("onlineorder_outbox_backlog"));

        assertUp(management("/actuator/health/liveness"));
        assertUp(management("/actuator/health/readiness"));
    }


    @Test
    void applicationPort_shouldKeepServingApiButNotActuator() {
        Assertions.assertEquals(
                HttpStatus.OK,
                restTemplate.getForEntity(server("/restaurants/menu"), String.class).getStatusCode()
        );

        // Not mapped on this port any more; anonymous requests fall under "authenticated".
        Assertions.assertEquals(
                HttpStatus.UNAUTHORIZED,
                restTemplate.getForEntity(server("/actuator/prometheus"), String.class).getStatusCode()
        );
        ResponseEntity<String> health = restTemplate.getForEntity(server("/actuator/health"), String.class);
        Assertions.assertNotEquals(HttpStatus.OK, health.getStatusCode());
    }


    @SuppressWarnings("rawtypes")
    private void assertUp(String url) {
        ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);
        Assertions.assertEquals(HttpStatus.OK, response.getStatusCode(), url);
        Assertions.assertNotNull(response.getBody());
        Assertions.assertEquals("UP", response.getBody().get("status"), url);
    }


    private String management(String path) {
        return "http://localhost:" + managementPort + path;
    }


    private String server(String path) {
        return "http://localhost:" + serverPort + path;
    }
}
