package com.laioffer.onlineorder.datasource;


import com.laioffer.onlineorder.repository.RestaurantRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.TimeGauge;
import org.flywaydb.core.Flyway;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.health.DataSourceHealthIndicator;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;


import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;


import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;


/**
 * Read/write splitting on a real PostgreSQL primary and a streaming replica of it (official image, pg_basebackup):
 * which transactions read the replica, read-your-writes after a session's own write, and that a lagging, stopped or
 * hung replica is taken out of rotation and rejoins when it recovers. The full order flow with routing on is in
 * {@code FulfilmentReplicaRoutingContainerTests}.
 *
 * <p>Thresholds are short so the tests run quickly: a replica more than 1s behind gets no reads, the monitor checks
 * every 200ms, and a session reads the primary for 2s after its own write.
 */
@SpringBootTest(properties = {
        // Sessions live in the MockMvc session; nothing here needs Redis.
        "spring.autoconfigure.exclude=" +
                "org.springframework.boot.session.autoconfigure.SessionAutoConfiguration," +
                "org.springframework.boot.session.data.redis.autoconfigure.SessionDataRedisAutoConfiguration"
})
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext
class ReadReplicaRoutingIntegrationTests {

    private static final String ADMIN = "demo@laifood.com";
    private static final String DEMO_PASSWORD = "demo123";
    private static final String MERCHANT = "merchant.burgerking@laifood.com";
    private static final String RIDER = "rider.alice@laifood.com";
    private static final Duration STICKY_WINDOW = Duration.ofSeconds(2);
    private static final Duration WAIT = Duration.ofSeconds(30);

    @Container
    static PostgresReplicationContainers DATABASES = new PostgresReplicationContainers();


    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        DATABASES.register(registry);
        registry.add("spring.flyway.locations", () -> "classpath:db/migration,classpath:db/demo");
        registry.add("spring.session.store-type", () -> "none");
        registry.add("spring.cache.type", () -> "none");
        registry.add("app.kafka.enabled", () -> "false");
        registry.add("app.outbox.publisher-enabled", () -> "false");
        registry.add("app.cleanup.enabled", () -> "false");
        registry.add("app.security.rate-limit.enabled", () -> "false");
        registry.add("app.datasource.replica.sticky-window", () -> STICKY_WINDOW.toMillis() + "ms");
        registry.add("app.datasource.replica.max-lag", () -> "1s");
        registry.add("app.datasource.replica.lag-check-interval", () -> "200ms");
    }


    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private ReplicaRouter router;

    @Autowired
    private RestaurantRepository restaurantRepository;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private ObjectMapper objectMapper;


    @BeforeEach
    void replicaIsRoutable() {
        await("the replica to be routable", () -> replica().isRoutable());
    }


    @AfterEach
    void restoreReplica() throws Exception {
        DATABASES.thawReplica();
        DATABASES.startReplicaServer();
        try {
            DATABASES.resumeReplay();
        } catch (java.sql.SQLException notUpYet) {
            // The next test's @BeforeEach waits until the replica is back.
        }
    }


    @Test
    void readOnlyTransactionsReadTheReplica_everythingElseThePrimary() {
        Assertions.assertTrue(inReadOnlyTransaction(this::inRecovery), "read-only transaction on the replica");
        Assertions.assertEquals("onlineorder-db-replica-1", inReadOnlyTransaction(() ->
                jdbcTemplate.queryForObject("SELECT current_setting('application_name')", String.class)));
        Assertions.assertFalse(inWriteTransaction(this::inRecovery), "write transaction on the primary");
        Assertions.assertFalse(inRecovery(), "statement outside a transaction on the primary");

        // Spring Data runs a CRUD finder outside a transaction in a read-only transaction of its own; it stays on the
        // primary, because replica reads are opt-in per service method.
        double replicaReads = reads("replica-1", ReplicaRouter.REASON_REPLICA);
        Assertions.assertTrue(restaurantRepository.existsById(firstRestaurantId()));
        Assertions.assertTrue(restaurantRepository.findById(firstRestaurantId()).isPresent());
        Assertions.assertEquals(replicaReads, reads("replica-1", ReplicaRouter.REASON_REPLICA));

        // The read-only flag reaches PostgreSQL: a write or a row lock in a read-only transaction fails instead of
        // reaching a replica (or slipping through on the primary).
        Assertions.assertThrows(DataAccessException.class, () -> inReadOnlyTransaction(() ->
                jdbcTemplate.update("UPDATE restaurants SET phone = phone WHERE id = ?", firstRestaurantId())));
        Assertions.assertThrows(DataAccessException.class, () -> inReadOnlyTransaction(() ->
                jdbcTemplate.queryForList("SELECT id FROM restaurants WHERE id = ? FOR UPDATE", firstRestaurantId())));
        // One database health check, on the routing DataSource (outside a transaction: the primary). Flyway migrates
        // the primary over its own connections.
        Assertions.assertInstanceOf(DataSourceHealthIndicator.class, applicationContext.getBean("dbHealthContributor"));
        Assertions.assertEquals(org.springframework.boot.health.contributor.Status.UP,
                applicationContext.getBean("dbHealthContributor", DataSourceHealthIndicator.class).health().getStatus());
        DataSource migrations = applicationContext.getBean(Flyway.class).getConfiguration().getDataSource();
        Assertions.assertEquals(DATABASES.primaryJdbcUrl(), ((SimpleDriverDataSource) migrations).getUrl());
    }


    @Test
    void annotatedReadPathsAreServedByTheReplica() throws Exception {
        MockHttpSession admin = loginWithoutStickyWindow(ADMIN, DEMO_PASSWORD);
        MockHttpSession merchant = loginWithoutStickyWindow(MERCHANT, "merchant123");
        MockHttpSession rider = loginWithoutStickyWindow(RIDER, "rider123");

        assertServedByTheReplica(get("/restaurants/menu"));
        assertServedByTheReplica(get("/restaurant/" + firstRestaurantId() + "/menu"));
        assertServedByTheReplica(get("/orders").session(admin));
        assertServedByTheReplica(get("/notifications").session(admin));
        assertServedByTheReplica(get("/admin/audit-log").session(admin));
        assertServedByTheReplica(get("/admin/merchants").session(admin));
        assertServedByTheReplica(get("/admin/riders").session(admin));
        assertServedByTheReplica(get("/admin/deliveries").session(admin));
        assertServedByTheReplica(get("/merchant/orders").session(merchant));
        assertServedByTheReplica(get("/rider/deliveries").session(rider));
    }


    @Test
    void aSessionReadsThePrimaryRightAfterItsOwnWrite_andTheReplicaOnceTheWindowHasPassed() throws Exception {
        MockHttpSession customer = signUpAndLogin();
        assertServedByTheReplica(get("/orders").session(customer));

        DATABASES.pauseReplay();
        long orderId;
        try {
            // The replica replays nothing from here on, so only the primary has the order placed next.
            mockMvc.perform(post("/cart").session(customer)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"menu_id\": " + firstMenuItemId() + "}"))
                    .andExpect(status().isOk());
            String placed = mockMvc.perform(post("/cart/checkout").session(customer)
                            .header("Idempotency-Key", "replica-" + System.nanoTime()))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            orderId = objectMapper.readTree(placed).get("id").asLong();

            double sticky = reads(ReplicaRouter.PRIMARY, ReplicaRouter.REASON_STICKY);
            double replicaReads = reads("replica-1", ReplicaRouter.REASON_REPLICA);
            Assertions.assertTrue(ordersOf(customer).contains("\"id\":" + orderId), "the session sees its own order");
            Assertions.assertEquals(sticky + 1, reads(ReplicaRouter.PRIMARY, ReplicaRouter.REASON_STICKY));
            Assertions.assertEquals(replicaReads, reads("replica-1", ReplicaRouter.REASON_REPLICA));
            Assertions.assertEquals(0L, DATABASES.queryReplica(
                    "SELECT COUNT(*) FROM orders WHERE id = " + orderId, Long.class), "the replica has not replayed it");
        } finally {
            DATABASES.resumeReplay();
        }

        await("the replica to catch up", () -> replica().isRoutable());
        Thread.sleep(STICKY_WINDOW.toMillis() + 100);
        double replicaReads = reads("replica-1", ReplicaRouter.REASON_REPLICA);
        Assertions.assertTrue(ordersOf(customer).contains("\"id\":" + orderId));
        Assertions.assertEquals(replicaReads + 1, reads("replica-1", ReplicaRouter.REASON_REPLICA));
    }


    @Test
    void aLaggingReplicaGetsNoReadsUntilItCatchesUp() throws Exception {
        DATABASES.pauseReplay();
        try {
            // The primary moves on; the paused replica does not.
            jdbcTemplate.update("UPDATE restaurants SET phone = phone WHERE id = ?", firstRestaurantId());
            await("the replica to be marked lagging", () -> replica().state() == ReplicaTarget.State.LAGGING);
            Assertions.assertTrue(lagGauge() > 1.0, "lag " + lagGauge());
            Assertions.assertEquals(0.0, routableGauge());

            double fallbacks = reads(ReplicaRouter.PRIMARY, ReplicaRouter.REASON_NO_REPLICA);
            Assertions.assertFalse(inReadOnlyTransaction(this::inRecovery), "read-only transaction on the primary");
            Assertions.assertEquals(fallbacks + 1, reads(ReplicaRouter.PRIMARY, ReplicaRouter.REASON_NO_REPLICA));
        } finally {
            DATABASES.resumeReplay();
        }
        await("the replica to catch up", () -> replica().isRoutable());
        Assertions.assertEquals(1.0, routableGauge());
        Assertions.assertTrue(inReadOnlyTransaction(this::inRecovery));
    }


    @Test
    void aStoppedReplicaGetsNoReadsAndRejoinsWhenItIsBack() throws Exception {
        DATABASES.stopReplicaServer();
        await("the replica to be marked unreachable", () -> replica().state() == ReplicaTarget.State.UNREACHABLE);
        Assertions.assertTrue(Double.isNaN(lagGauge()));

        double fallbacks = reads(ReplicaRouter.PRIMARY, ReplicaRouter.REASON_NO_REPLICA);
        Assertions.assertFalse(inReadOnlyTransaction(this::inRecovery));
        Assertions.assertEquals(fallbacks + 1, reads(ReplicaRouter.PRIMARY, ReplicaRouter.REASON_NO_REPLICA));
        mockMvc.perform(get("/restaurants/menu")).andExpect(status().isOk());

        DATABASES.startReplicaServer();
        await("the restarted replica to be routable", () -> replica().isRoutable());
        Assertions.assertTrue(inReadOnlyTransaction(this::inRecovery));
    }


    @Test
    void aHungReplicaIsTakenOutOfRotationWithinTheCheckTimeout() throws Exception {
        DATABASES.freezeReplica();
        try {
            await("the hung replica to be marked unreachable", () -> replica().state() == ReplicaTarget.State.UNREACHABLE);
            Instant start = Instant.now();
            Assertions.assertFalse(inReadOnlyTransaction(this::inRecovery));
            mockMvc.perform(get("/restaurants/menu")).andExpect(status().isOk());
            Duration elapsed = Duration.between(start, Instant.now());
            Assertions.assertTrue(elapsed.compareTo(Duration.ofSeconds(1)) < 0, "reads waited " + elapsed);
        } finally {
            DATABASES.thawReplica();
        }
        await("the thawed replica to be routable", () -> replica().isRoutable());
    }


    private void assertServedByTheReplica(MockHttpServletRequestBuilder request) throws Exception {
        double replicaReads = reads("replica-1", ReplicaRouter.REASON_REPLICA);
        double primaryReads = reads(ReplicaRouter.PRIMARY, ReplicaRouter.REASON_STICKY)
                + reads(ReplicaRouter.PRIMARY, ReplicaRouter.REASON_NO_REPLICA)
                + reads(ReplicaRouter.PRIMARY, ReplicaRouter.REASON_REPLICA_FAILED);
        MvcResult result = mockMvc.perform(request).andExpect(status().isOk()).andReturn();
        String uri = result.getRequest().getRequestURI();
        Assertions.assertTrue(reads("replica-1", ReplicaRouter.REASON_REPLICA) > replicaReads, uri + " read the replica");
        Assertions.assertEquals(primaryReads, reads(ReplicaRouter.PRIMARY, ReplicaRouter.REASON_STICKY)
                + reads(ReplicaRouter.PRIMARY, ReplicaRouter.REASON_NO_REPLICA)
                + reads(ReplicaRouter.PRIMARY, ReplicaRouter.REASON_REPLICA_FAILED), uri + " read nothing on the primary");
    }


    private String ordersOf(MockHttpSession session) throws Exception {
        return mockMvc.perform(get("/orders").session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }


    private MockHttpSession signUpAndLogin() throws Exception {
        String email = "replica-" + System.nanoTime() + "@example.com";
        mockMvc.perform(post("/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"demo123\","
                                + "\"first_name\":\"Replica\",\"last_name\":\"Reader\"}"))
                .andExpect(status().isCreated());
        return loginWithoutStickyWindow(email, "demo123");
    }


    /**
     * Logs in; a login records the successful attempt, a write, so the session starts inside its read-your-writes
     * window. The window is dropped here so the next read shows where reads go outside it.
     */
    private MockHttpSession loginWithoutStickyWindow(String email, String password) throws Exception {
        MvcResult login = mockMvc.perform(post("/login")
                        .param("username", email)
                        .param("password", password))
                .andExpect(status().isOk())
                .andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        Assertions.assertNotNull(session);
        Assertions.assertNotNull(session.getAttribute(ReadYourWritesTracker.LAST_WRITE_ATTRIBUTE),
                "the login's write started the window");
        session.removeAttribute(ReadYourWritesTracker.LAST_WRITE_ATTRIBUTE);
        return session;
    }


    private ReplicaTarget replica() {
        return router.replicas().get(0);
    }


    private boolean inRecovery() {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject("SELECT pg_is_in_recovery()", Boolean.class));
    }


    private <T> T inReadOnlyTransaction(Supplier<T> work) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setReadOnly(true);
        template.setName("ReadReplicaRoutingIntegrationTests.read");
        return template.execute(status -> work.get());
    }


    private <T> T inWriteTransaction(Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> work.get());
    }


    private long firstRestaurantId() {
        return jdbcTemplate.queryForObject("SELECT MIN(id) FROM restaurants", Long.class);
    }


    private long firstMenuItemId() {
        return jdbcTemplate.queryForObject("SELECT MIN(id) FROM menu_items", Long.class);
    }


    private double reads(String target, String reason) {
        Counter counter = meterRegistry.find(ReplicaRouter.READS_METRIC).tag("target", target).tag("reason", reason).counter();
        Assertions.assertNotNull(counter, target + "/" + reason);
        return counter.count();
    }


    private double lagGauge() {
        TimeGauge gauge = meterRegistry.find("onlineorder.db.replica.lag").tag("replica", "replica-1").timeGauge();
        Assertions.assertNotNull(gauge);
        return gauge.value(java.util.concurrent.TimeUnit.SECONDS);
    }


    private double routableGauge() {
        Gauge gauge = meterRegistry.find("onlineorder.db.replica.routable").tag("replica", "replica-1").gauge();
        Assertions.assertNotNull(gauge);
        return gauge.value();
    }


    private static void await(String what, BooleanSupplier condition) {
        Instant deadline = Instant.now().plus(WAIT);
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) {
                Assertions.fail("Timed out after " + WAIT + " waiting for " + what);
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(ex);
            }
        }
    }
}
