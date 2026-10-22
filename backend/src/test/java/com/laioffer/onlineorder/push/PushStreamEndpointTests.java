package com.laioffer.onlineorder.push;


import com.laioffer.onlineorder.audit.AuditActor;
import com.laioffer.onlineorder.service.CustomerService;
import com.laioffer.onlineorder.staff.AdminMerchantDto;
import com.laioffer.onlineorder.staff.AdminRiderDto;
import com.laioffer.onlineorder.staff.CreateMerchantBody;
import com.laioffer.onlineorder.staff.CreateRiderBody;
import com.laioffer.onlineorder.staff.StaffProvisioningService;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockAsyncContext;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;


import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;


/**
 * GET /events/stream through the security chain and Spring MVC async (MockMvc), and once over a real socket: who may
 * open a stream (401, 403 for a deactivated merchant or rider, 429 and 503 at the limits), what it sends (retry first,
 * a signal once the writer's transaction committed, nothing for a rollback, forbidden after a deactivation) and how it
 * ends (its own timeout, the instance shutting down). The keepalive tick is called directly; its thread only runs
 * hourly here.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                // Sessions live in the servlet container (and in MockMvc's session) instead of Redis.
                "spring.autoconfigure.exclude=" +
                        "org.springframework.boot.session.autoconfigure.SessionAutoConfiguration," +
                        "org.springframework.boot.session.data.redis.autoconfigure.SessionDataRedisAutoConfiguration",
                "spring.session.store-type=none",
                "spring.cache.type=none",
                "app.security.rate-limit.enabled=false",
                "app.push.max-streams-per-user=2",
                "app.push.max-streams=4",
                "app.push.keepalive-interval=1h"
        }
)
@AutoConfigureMockMvc
@ActiveProfiles("h2")
class PushStreamEndpointTests {

    private static final Duration WAIT = Duration.ofSeconds(10);
    private static final String PASSWORD = "Push-secret-42";
    private static final String OPEN = "retry: 5000\n\n";

    @LocalServerPort
    private int port;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CustomerService customerService;

    @Autowired
    private StaffProvisioningService staffProvisioningService;

    @Autowired
    private PushSignals pushSignals;

    @Autowired
    private PushStreamRegistry registry;

    @Autowired
    private PushStreamHousekeeping housekeeping;

    @Autowired
    private TransactionTemplate transactionTemplate;


    @AfterEach
    void closeStreams() {
        registry.close();
        registry.open();
    }


    @Test
    void anAnonymousRequest_shouldBeRefusedWith401() throws Exception {
        mockMvc.perform(get("/events/stream").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        Assertions.assertEquals(0, registry.size());
    }


    @Test
    void aCustomersStream_shouldStartWithRetryAndGetASignalOnlyAfterTheCommit() throws Exception {
        Login customer = signUp();
        MvcResult stream = openStream(customer.session());
        MockHttpServletResponse response = stream.getResponse();

        Assertions.assertEquals(OPEN, response.getContentAsString(), "retry is the first frame");
        Assertions.assertTrue(response.getContentType().startsWith(MediaType.TEXT_EVENT_STREAM_VALUE),
                response.getContentType());
        Assertions.assertEquals("no-cache", response.getHeader("Cache-Control"));
        Assertions.assertEquals("no", response.getHeader("X-Accel-Buffering"));
        Assertions.assertEquals(Duration.ofMinutes(30).toMillis(), stream.getRequest().getAsyncContext().getTimeout(),
                "app.push.stream-timeout is the async timeout, not spring.mvc.async.request-timeout");

        String signal = "event: signal\ndata: {\"topic\":\"notifications\"}\n\n";
        transactionTemplate.executeWithoutResult(status -> {
            pushSignals.publish(PushRecipient.customer(customer.id()), PushTopic.NOTIFICATIONS);
            Assertions.assertEquals(OPEN, contentOf(response), "nothing before the commit");
        });
        awaitContent(response, OPEN + signal);

        // A rolled-back change signals nothing: the next committed signal is the next frame.
        transactionTemplate.executeWithoutResult(status -> {
            pushSignals.publish(PushRecipient.customer(customer.id()), PushTopic.MERCHANT_ORDERS);
            status.setRollbackOnly();
        });
        pushSignals.publish(PushRecipient.customer(customer.id()), PushTopic.RIDER_DELIVERIES);
        awaitContent(response, OPEN + signal + "event: signal\ndata: {\"topic\":\"rider.deliveries\"}\n\n");

        housekeeping.tick();
        Assertions.assertTrue(contentOf(response).endsWith(":keepalive\n\n"));
        Assertions.assertEquals(1, registry.size());
    }


    @Test
    void aMerchantsStream_shouldGetItsRestaurantsSignalsUntilTheMerchantIsDeactivated() throws Exception {
        Login admin = login("demo@laifood.com", "demo123");
        String email = "push-merchant-" + System.nanoTime() + "@example.com";
        AdminMerchantDto merchant = staffProvisioningService.createMerchant(
                new CreateMerchantBody(email, PASSWORD, "Push", "Merchant", 1L), AuditActor.admin(admin.id()));
        Login login = login(email, PASSWORD);
        MvcResult stream = openStream(login.session());
        MockHttpServletResponse response = stream.getResponse();

        pushSignals.publish(PushRecipient.restaurant(2L), PushTopic.MERCHANT_ORDERS);
        pushSignals.publish(PushRecipient.restaurant(1L), PushTopic.MERCHANT_AFTER_SALES);
        awaitContent(response, OPEN + "event: signal\ndata: {\"topic\":\"merchant.after_sales\"}\n\n");

        housekeeping.tick();
        Assertions.assertEquals(OPEN + "event: signal\ndata: {\"topic\":\"merchant.after_sales\"}\n\n:keepalive\n\n",
                contentOf(response), "an active merchant keeps the stream");

        staffProvisioningService.setMerchantActive(merchant.merchantId(), false, AuditActor.admin(admin.id()));
        CountDownLatch completed = dispatched(stream);
        housekeeping.tick();

        Assertions.assertTrue(completed.await(WAIT.toMillis(), TimeUnit.MILLISECONDS), "the stream was completed");
        Assertions.assertTrue(contentOf(response).endsWith(":keepalive\n\nevent: forbidden\ndata: {}\n\n"),
                contentOf(response));
        mockMvc.perform(asyncDispatch(stream)).andExpect(status().isOk());
        Assertions.assertEquals(0, registry.size());

        mockMvc.perform(get("/events/stream").session(login.session()).accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isForbidden())
                .andExpect(request().asyncNotStarted())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }


    @Test
    void aRidersStream_shouldGetTheRidersSignalsUntilTheRiderIsDeactivated() throws Exception {
        Login admin = login("demo@laifood.com", "demo123");
        String email = "push-rider-" + System.nanoTime() + "@example.com";
        AdminRiderDto rider = staffProvisioningService.createRider(
                new CreateRiderBody(email, PASSWORD, "Push", "Rider", null), AuditActor.admin(admin.id()));
        Login login = login(email, PASSWORD);
        MvcResult stream = openStream(login.session());

        pushSignals.publish(PushRecipient.rider(rider.riderId()), PushTopic.RIDER_DELIVERIES);
        awaitContent(stream.getResponse(), OPEN + "event: signal\ndata: {\"topic\":\"rider.deliveries\"}\n\n");

        staffProvisioningService.updateRider(rider.riderId(), false, null, AuditActor.admin(admin.id()));
        CountDownLatch completed = dispatched(stream);
        housekeeping.tick();

        Assertions.assertTrue(completed.await(WAIT.toMillis(), TimeUnit.MILLISECONDS), "the stream was completed");
        Assertions.assertTrue(contentOf(stream.getResponse()).endsWith("event: forbidden\ndata: {}\n\n"));
        mockMvc.perform(get("/events/stream").session(login.session()).accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }


    @Test
    void streamsOverTheLimits_shouldBeRefusedWith429ForTheLoginAnd503ForTheInstance() throws Exception {
        Login first = signUp();
        openStream(first.session());
        openStream(first.session());

        mockMvc.perform(get("/events/stream").session(first.session()).accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("PUSH_STREAM_LIMIT"));

        Login second = signUp();
        openStream(second.session());
        openStream(second.session());
        mockMvc.perform(get("/events/stream").session(signUp().session()).accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));
        Assertions.assertEquals(4, registry.size());
    }


    @Test
    void aStreamThatTimesOut_shouldEndNormallyAndLeaveTheRegistry() throws Exception {
        MvcResult stream = openStream(signUp().session());
        MockAsyncContext asyncContext = (MockAsyncContext) stream.getRequest().getAsyncContext();
        CountDownLatch completed = dispatched(stream);

        for (AsyncListener listener : asyncContext.getListeners()) {
            listener.onTimeout(new AsyncEvent(asyncContext));
        }

        Assertions.assertTrue(completed.await(WAIT.toMillis(), TimeUnit.MILLISECONDS));
        mockMvc.perform(asyncDispatch(stream)).andExpect(status().isOk());
        Assertions.assertEquals(0, registry.size());
    }


    /**
     * Over a socket: the headers, retry flushed at once (before any signal), a signal, and the end of the response
     * when the instance closes its streams.
     */
    @Test
    void overHttp_theStreamShouldFlushEachFrameAsItIsWritten() throws Exception {
        String email = "push-http-" + System.nanoTime() + "@example.com";
        Long customerId = signUp(email).id();
        try (SseTestClient client = SseTestClient.open(baseUrl(), loginOverHttp(email))) {
            Assertions.assertEquals(200, client.status());
            Assertions.assertTrue(client.header("Content-Type").startsWith("text/event-stream"),
                    client.header("Content-Type"));
            Assertions.assertEquals("no-cache", client.header("Cache-Control"));
            Assertions.assertEquals("no", client.header("X-Accel-Buffering"));
            client.awaitLine("retry: 5000", WAIT);

            pushSignals.publish(PushRecipient.customer(customerId), PushTopic.NOTIFICATIONS);
            client.awaitSignal("notifications", WAIT);

            registry.close();
            client.awaitEnd(WAIT);
        }
    }


    private MvcResult openStream(MockHttpSession session) throws Exception {
        return mockMvc.perform(get("/events/stream").session(session).accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();
    }


    private static CountDownLatch dispatched(MvcResult stream) {
        CountDownLatch latch = new CountDownLatch(1);
        ((MockAsyncContext) stream.getRequest().getAsyncContext()).addDispatchHandler(latch::countDown);
        return latch;
    }


    private static void awaitContent(MockHttpServletResponse response, String expected) {
        Awaitility.await().atMost(WAIT).until(() -> contentOf(response).equals(expected));
    }


    private static String contentOf(MockHttpServletResponse response) {
        return new String(response.getContentAsByteArray(), StandardCharsets.UTF_8);
    }


    private Login signUp() throws Exception {
        return signUp("push-customer-" + System.nanoTime() + "@example.com");
    }


    private Login signUp(String email) throws Exception {
        customerService.signUp(email, PASSWORD, "Push", "Customer");
        return login(email, PASSWORD);
    }


    private Login login(String email, String password) throws Exception {
        MockHttpSession session = (MockHttpSession) mockMvc.perform(post("/login")
                        .param("username", email)
                        .param("password", password))
                .andExpect(status().isOk())
                .andReturn()
                .getRequest()
                .getSession(false);
        return new Login(customerService.getCustomerByEmail(email).id(), session);
    }


    private String loginOverHttp(String email) throws IOException, InterruptedException {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(baseUrl() + "/login"))
                        .header("Content-Type", MediaType.APPLICATION_FORM_URLENCODED_VALUE)
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "username=" + URLEncoder.encode(email, StandardCharsets.UTF_8)
                                        + "&password=" + URLEncoder.encode(PASSWORD, StandardCharsets.UTF_8)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        Assertions.assertEquals(200, response.statusCode(), response.body());
        String cookie = response.headers().firstValue("Set-Cookie").orElseThrow();
        return cookie.substring(0, cookie.indexOf(';'));
    }


    private String baseUrl() {
        return "http://localhost:" + port;
    }


    private record Login(Long id, MockHttpSession session) {
    }
}
