package com.laioffer.onlineorder;


import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;


import java.util.regex.Pattern;


@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.security.rate-limit.enabled=false",
                "spring.autoconfigure.exclude=org.springframework.boot.session.autoconfigure.SessionAutoConfiguration," +
                        "org.springframework.boot.session.data.redis.autoconfigure.SessionDataRedisAutoConfiguration",
                "spring.cache.type=none"
        }
)
// Spring Boot tests disable metrics export, and with it the Prometheus endpoint, unless asked for.
@AutoConfigureMetrics
@ActiveProfiles("h2")
@AutoConfigureTestRestTemplate
class MetricsConfigurationTests {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;


    @Test
    void prometheusEndpoint_shouldExposePoolThreadAndLatencyHistogramMetrics() {
        String adminCookie = login("demo@laifood.com", "demo123");
        Assertions.assertEquals(HttpStatus.OK, restTemplate.getForEntity(url("/restaurants/menu"), String.class).getStatusCode());

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, adminCookie);
        ResponseEntity<String> response = restTemplate.exchange(
                url("/actuator/prometheus"),
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );
        Assertions.assertEquals(HttpStatus.OK, response.getStatusCode());
        String scrape = response.getBody();
        Assertions.assertNotNull(scrape);

        assertSample(scrape, "hikaricp_connections_max\\{[^}]*pool=\"onlineorder-db\"[^}]*} 30\\.0");
        assertSample(scrape, "hikaricp_connections_acquire_seconds_bucket\\{[^}]*pool=\"onlineorder-db\"");
        // Needs server.tomcat.mbeanregistry.enabled; without it only tomcat_sessions_* is published.
        assertSample(scrape, "tomcat_threads_config_max_threads\\{[^}]*} 200\\.0");
        assertSample(scrape, "tomcat_threads_busy_threads\\{");
        assertSample(scrape, "http_server_requests_seconds_bucket\\{[^}]*le=\"0\\.25\"");
        assertSample(scrape, "http_server_requests_seconds_bucket\\{[^}]*le=\"2\\.0\"");

        // The six SLO boundaries plus +Inf would satisfy the two samples above on their own; the
        // percentiles histogram is what adds the finer buckets that p95/p99 queries need.
        long menuBucketBoundaries = scrape.lines()
                .filter(line -> line.startsWith("http_server_requests_seconds_bucket{"))
                .filter(line -> line.contains("uri=\"/restaurants/menu\""))
                .map(line -> line.replaceAll(".*le=\"([^\"]+)\".*", "$1"))
                .distinct()
                .count();
        Assertions.assertTrue(
                menuBucketBoundaries > 7,
                () -> "Expected percentiles-histogram buckets for http.server.requests, found " + menuBucketBoundaries
        );
    }


    private static void assertSample(String scrape, String regex) {
        Assertions.assertTrue(
                Pattern.compile(regex).matcher(scrape).find(),
                () -> "Prometheus scrape has no sample matching " + regex
        );
    }


    private String login(String email, String password) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("username", email);
        form.add("password", password);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        ResponseEntity<String> response = restTemplate.postForEntity(
                url("/login"),
                new HttpEntity<>(form, headers),
                String.class
        );
        Assertions.assertEquals(HttpStatus.OK, response.getStatusCode());
        String sessionCookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        Assertions.assertNotNull(sessionCookie);
        return sessionCookie.split(";", 2)[0];
    }


    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
