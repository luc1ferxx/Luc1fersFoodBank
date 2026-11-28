package io.github.luc1ferxx.foodbank;


import io.github.luc1ferxx.foodbank.PostgresRowLocks.RowLockHolder;
import io.github.luc1ferxx.foodbank.audit.AuditActor;
import io.github.luc1ferxx.foodbank.exception.ConflictException;
import io.github.luc1ferxx.foodbank.model.MenuItemStockDto;
import io.github.luc1ferxx.foodbank.model.OrderDto;
import io.github.luc1ferxx.foodbank.model.PaymentCheckoutBody;
import io.github.luc1ferxx.foodbank.service.PaymentService;
import io.github.luc1ferxx.foodbank.service.StockService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;


import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;


/**
 * Menu item stock on PostgreSQL, with its races. They do not rely on thread timing: the commands queue on the item's
 * row behind {@link PostgresRowLocks} in both orders (alternating rounds), so each serial outcome is reached on every
 * run. The carts are filled before the row is held, since the holder's FOR UPDATE blocks the FOR KEY SHARE that adding
 * a cart line takes through its foreign key. Kafka and the background jobs are off, so nothing else waits on a lock.
 */
@SpringBootTest(properties = {
        // Sessions live in the MockMvc session instead of Redis.
        "spring.autoconfigure.exclude=" +
                "org.springframework.boot.session.autoconfigure.SessionAutoConfiguration," +
                "org.springframework.boot.session.data.redis.autoconfigure.SessionDataRedisAutoConfiguration"
})
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class StockPostgresIntegrationTests extends AbstractStockIntegrationTests {

    private static final PaymentCheckoutBody CARD_BODY =
            new PaymentCheckoutBody("Stock Tester", "4242424242424242", "12/30", "123");

    private static final int ROUNDS = 4;

    @Container
    static PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("onlineorder")
            .withUsername("postgres")
            .withPassword("secret");


    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.locations", () -> "classpath:db/migration,classpath:db/demo");
        registry.add("spring.session.store-type", () -> "none");
        registry.add("spring.cache.type", () -> "none");
        registry.add("app.kafka.enabled", () -> "false");
        registry.add("app.outbox.publisher-enabled", () -> "false");
        registry.add("app.cleanup.enabled", () -> "false");
        registry.add("app.security.rate-limit.enabled", () -> "false");
    }


    @Autowired
    private PaymentService paymentService;


    /**
     * The last one, wanted by two customers queued on its row: the one queued first pays, the other gets the 409 when
     * it takes, after the early check, and it is sold once. The refused checkout leaves nothing behind.
     */
    @Test
    void twoCheckoutsOfTheLastOne_shouldSellItOnce() throws Exception {
        long restaurant = restaurant();
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            for (int round = 0; round < ROUNDS; round++) {
                String inRound = "round " + round;
                String name = "Burger " + round;
                long burger = menuItem(restaurant, name, 1);
                long first = shopper(burger, 1);
                long second = shopper(burger, 1);
                String fullCart = cart(first);
                boolean firstQueuedFirst = round % 2 == 0;

                Future<String> firstCheckout;
                Future<String> secondCheckout;
                try (RowLockHolder row = PostgresRowLocks.holdRowLock(
                        executor, transactionManager, jdbcTemplate, "menu_items", burger)) {
                    if (firstQueuedFirst) {
                        firstCheckout = row.queue(() -> payOutcome(first));
                        secondCheckout = row.queue(() -> payOutcome(second));
                    } else {
                        secondCheckout = row.queue(() -> payOutcome(second));
                        firstCheckout = row.queue(() -> payOutcome(first));
                    }
                    row.release();
                }

                Assertions.assertEquals(firstQueuedFirst ? "PAID" : refused(name),
                        firstCheckout.get(20, TimeUnit.SECONDS), inRound);
                Assertions.assertEquals(firstQueuedFirst ? refused(name) : "PAID",
                        secondCheckout.get(20, TimeUnit.SECONDS), inRound);
                Assertions.assertEquals(0, stock(burger), inRound);
                Assertions.assertEquals(1, markedLines(burger), inRound);
                Assertions.assertEquals(1, orders(first) + orders(second), inRound);
                assertRefusedLeftNothingBehind(firstQueuedFirst ? second : first, fullCart, burger, inRound);
            }
        } finally {
            executor.shutdownNow();
        }
    }


    /**
     * X wants A (5 left) and B (1 left), Y wants B; they queue on B's row. X takes A before it waits for B (ascending
     * ids; X holds A's row while it waits), so when Y gets B first, X's 409 rolls back its take of A as well.
     */
    @Test
    void aCheckoutRefusedForItsSecondItem_shouldRollBackTheTakeOfItsFirst() throws Exception {
        long restaurant = restaurant();
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            for (int round = 0; round < ROUNDS; round++) {
                String inRound = "round " + round;
                long a = menuItem(restaurant, "Burger " + round, 5);
                long b = menuItem(restaurant, "Shake " + round, 1);
                Assertions.assertTrue(a < b, inRound);
                long x = shopper(a, 1);
                addToCart(x, b, 1);
                long y = shopper(b, 1);
                String xCart = cart(x);
                String yCart = cart(y);
                boolean xFirst = round % 2 == 0;

                Future<String> xCheckout;
                Future<String> yCheckout;
                try (RowLockHolder row = PostgresRowLocks.holdRowLock(
                        executor, transactionManager, jdbcTemplate, "menu_items", b)) {
                    if (xFirst) {
                        xCheckout = row.queue(() -> payOutcome(x));
                        yCheckout = row.queue(() -> payOutcome(y));
                    } else {
                        yCheckout = row.queue(() -> payOutcome(y));
                        xCheckout = row.queue(() -> payOutcome(x));
                    }
                    assertRowHeldElsewhere(a, "X waits for B holding A, " + inRound);
                    row.release();
                }

                String shakeRefused = refused("Shake " + round);
                Assertions.assertEquals(xFirst ? "PAID" : shakeRefused, xCheckout.get(20, TimeUnit.SECONDS), inRound);
                Assertions.assertEquals(xFirst ? shakeRefused : "PAID", yCheckout.get(20, TimeUnit.SECONDS), inRound);
                Assertions.assertEquals(xFirst ? 4 : 5, stock(a), inRound);
                Assertions.assertEquals(0, stock(b), inRound);
                Assertions.assertEquals(xFirst ? 1 : 0, markedLines(a), inRound);
                Assertions.assertEquals(1, markedLines(b), inRound);
                Assertions.assertEquals(xFirst ? 1 : 0, orders(x), inRound);
                Assertions.assertEquals(xFirst ? 0 : 1, orders(y), inRound);
                assertRefusedLeftNothingBehind(xFirst ? y : x, xFirst ? yCart : xCart, b, inRound);
            }
        } finally {
            executor.shutdownNow();
        }
    }


    /**
     * A cart that got the shake (the higher id) first still has its burger taken first, and its order's cancel gives
     * the burger back first: with the shake's row held, each waits for it holding the burger's row already. Every
     * checkout and cancel of the two locks them in that order, so none can deadlock with another.
     */
    @Test
    void checkoutAndCancel_shouldLockTheItemsInAscendingId_whateverOrderTheCartGotThem() throws Exception {
        long restaurant = restaurant();
        long burger = menuItem(restaurant, "Burger", 5);
        long shake = menuItem(restaurant, "Shake", 5);
        Assertions.assertTrue(burger < shake, "the burger has the lower id");
        long customer = shopper(shake, 1);
        addToCart(customer, burger, 1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> checkout;
            try (RowLockHolder row = PostgresRowLocks.holdRowLock(
                    executor, transactionManager, jdbcTemplate, "menu_items", shake)) {
                checkout = row.queue(() -> payOutcome(customer));
                assertRowHeldElsewhere(burger, "the checkout waits for the shake holding the burger");
                row.release();
            }
            Assertions.assertEquals("PAID", checkout.get(20, TimeUnit.SECONDS));
            long orderId = orderIdOf(customer);

            Future<String> cancel;
            try (RowLockHolder row = PostgresRowLocks.holdRowLock(
                    executor, transactionManager, jdbcTemplate, "menu_items", shake)) {
                cancel = row.queue(() -> orderService.idempotentCancelOrder(customer, orderId, "cancel-" + orderId)
                        .status());
                assertRowHeldElsewhere(burger, "the cancel waits for the shake holding the burger");
                row.release();
            }
            Assertions.assertEquals("CANCELLED", cancel.get(20, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
        Assertions.assertEquals(5, stock(burger));
        Assertions.assertEquals(5, stock(shake));
    }


    /**
     * An admin removes the limit while a checkout of the item waits for its row, queued in both orders. Removal first:
     * the take changes no row, the re-read finds no limit, and the checkout pays without a mark. Checkout first: it
     * takes and marks, and the removal makes the item unlimited, so the order's cancel gives nothing back but clears
     * the mark. Either way the item ends unlimited.
     */
    @Test
    void aLimitRemovedWhileACheckoutWaits_shouldLetItPayInBothOrders() throws Exception {
        long restaurant = restaurant();
        AuditActor admin = AuditActor.admin(adminId());
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            for (int round = 0; round < ROUNDS; round++) {
                String inRound = "round " + round;
                long burger = menuItem(restaurant, "Burger " + round, 1);
                long customer = shopper(burger, 1);
                Callable<MenuItemStockDto> removal = () -> stockService.removeStockLimit(burger, admin);
                Callable<String> checkout = () -> payOutcome(customer);
                boolean removalFirst = round % 2 == 0;

                Future<MenuItemStockDto> removed;
                Future<String> paid;
                try (RowLockHolder row = PostgresRowLocks.holdRowLock(
                        executor, transactionManager, jdbcTemplate, "menu_items", burger)) {
                    if (removalFirst) {
                        removed = row.queue(removal);
                        paid = row.queue(checkout);
                    } else {
                        paid = row.queue(checkout);
                        removed = row.queue(removal);
                    }
                    row.release();
                }

                Assertions.assertEquals(new MenuItemStockDto(burger, null), removed.get(20, TimeUnit.SECONDS), inRound);
                Assertions.assertEquals("PAID", paid.get(20, TimeUnit.SECONDS), inRound);
                Assertions.assertNull(stock(burger), inRound);
                long orderId = orderIdOf(customer);
                Assertions.assertEquals(Map.of(burger, !removalFirst), marks(orderId), inRound);

                Assertions.assertEquals("CANCELLED",
                        orderService.idempotentCancelOrder(customer, orderId, "cancel-" + orderId).status(), inRound);
                Assertions.assertNull(stock(burger), inRound);
                Assertions.assertEquals(Map.of(burger, false), marks(orderId), inRound);
            }
        } finally {
            executor.shutdownNow();
        }
    }


    /**
     * Adding the item to a cart takes FOR KEY SHARE on its row (the cart line's foreign key), which the take's FOR NO
     * KEY UPDATE does not block: a customer fills a cart while a checkout of the item has taken its stock and not
     * committed. Were the add blocked, the pool's lock_timeout would fail it.
     */
    @Test
    void aCartAdd_shouldNotWaitForATakeThatHasNotCommitted() throws Exception {
        long burger = menuItem(restaurant(), "Burger", 5);
        long customer = signUp("stock-cart-add-" + System.nanoTime() + "@example.com");
        CountDownLatch taken = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Object> take = executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                Assertions.assertEquals(1, menuItemRepository.takeStock(burger, 1));
                taken.countDown();
                try {
                    Assertions.assertTrue(commit.await(30, TimeUnit.SECONDS), "the take was never let commit");
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(ex);
                }
                return null;
            }));
            Assertions.assertTrue(taken.await(30, TimeUnit.SECONDS), "the take never ran");

            cartService.addMenuItemToCart(customer, burger);

            Assertions.assertFalse(take.isDone(), "the take has not committed");
            commit.countDown();
            take.get(20, TimeUnit.SECONDS);
        } finally {
            commit.countDown();
            executor.shutdownNow();
        }
        Assertions.assertEquals(4, stock(burger));
        Assertions.assertEquals("4.50 USD [" + burger + "x1]", cart(customer));
    }


    /**
     * Order O took one of two, and customer Z wants one: O's cancel and Z's checkout queue on the row in both orders.
     * Z pays either way and one is left, neither given back nor taken twice.
     */
    @Test
    void aCheckoutRacingACancel_shouldLeaveTheCancelledOneForSale() throws Exception {
        long restaurant = restaurant();
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            for (int round = 0; round < ROUNDS; round++) {
                String inRound = "round " + round;
                long burger = menuItem(restaurant, "Burger " + round, 2);
                long owner = shopper(burger, 1);
                OrderDto o = cartService.checkout(owner, "PAID");
                Assertions.assertEquals(1, stock(burger), inRound);
                long z = shopper(burger, 1);
                Callable<String> cancelCommand =
                        () -> orderService.idempotentCancelOrder(owner, o.id(), "cancel-" + o.id()).status();
                Callable<String> checkoutCommand = () -> payOutcome(z);
                boolean cancelFirst = round % 2 == 0;

                Future<String> cancelled;
                Future<String> paid;
                try (RowLockHolder row = PostgresRowLocks.holdRowLock(
                        executor, transactionManager, jdbcTemplate, "menu_items", burger)) {
                    if (cancelFirst) {
                        cancelled = row.queue(cancelCommand);
                        paid = row.queue(checkoutCommand);
                    } else {
                        paid = row.queue(checkoutCommand);
                        cancelled = row.queue(cancelCommand);
                    }
                    row.release();
                }

                Assertions.assertEquals("CANCELLED", cancelled.get(20, TimeUnit.SECONDS), inRound);
                Assertions.assertEquals("PAID", paid.get(20, TimeUnit.SECONDS), inRound);
                Assertions.assertEquals(1, stock(burger), inRound);
                Assertions.assertEquals(Map.of(burger, false), marks(o.id()), inRound);
                Assertions.assertEquals(1, markedLines(burger), inRound);
            }
        } finally {
            executor.shutdownNow();
        }
    }


    /**
     * Twenty checkouts of an item with seven left, let go at once: exactly seven pay and thirteen get the 409, from the
     * early check or from the take, and nothing else happens (no lock timeout, no oversell).
     */
    @Test
    void aStormOfCheckouts_shouldSellExactlyWhatIsLeft() throws Exception {
        long burger = menuItem(restaurant(), "Burger", 7);
        List<Long> shoppers = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            shoppers.add(shopper(burger, 1));
        }
        // Twenty connections at once, below the pool's thirty.
        ExecutorService executor = Executors.newFixedThreadPool(shoppers.size());
        try {
            CountDownLatch ready = new CountDownLatch(shoppers.size());
            CountDownLatch start = new CountDownLatch(1);
            List<Future<String>> checkouts = new ArrayList<>();
            for (long shopper : shoppers) {
                checkouts.add(executor.submit(() -> {
                    ready.countDown();
                    Assertions.assertTrue(start.await(30, TimeUnit.SECONDS), "never started");
                    return payOutcome(shopper);
                }));
            }
            Assertions.assertTrue(ready.await(30, TimeUnit.SECONDS), "the checkouts never got their threads");
            start.countDown();

            Map<String, Integer> outcomes = new HashMap<>();
            for (Future<String> checkout : checkouts) {
                outcomes.merge(checkout.get(60, TimeUnit.SECONDS), 1, Integer::sum);
            }
            Assertions.assertEquals(Map.of("PAID", 7, refused("Burger"), 13), outcomes);
            Assertions.assertEquals(0, stock(burger));
            Assertions.assertEquals(7, markedLines(burger));
        } finally {
            executor.shutdownNow();
        }
    }


    // ---- helpers ----


    /**
     * A new customer with {@code quantity} of the item in the cart, filled before any row is held.
     */
    private long shopper(long menuItemId, int quantity) {
        long customerId = signUp("stock-race-" + System.nanoTime() + "@example.com");
        addToCart(customerId, menuItemId, quantity);
        return customerId;
    }


    /**
     * The customer's POST /payments/checkout through the service: the order's status, or the code and message of the
     * 409.
     */
    private String payOutcome(long customerId) {
        try {
            return paymentService.payAndCheckout(customerId, "pay-" + customerId, CARD_BODY).status();
        } catch (ConflictException ex) {
            return "CONFLICT|" + ex.getCode() + "|" + ex.getMessage();
        }
    }


    private int markedLines(long menuItemId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM order_history_items WHERE menu_item_id = ? AND stock_reserved = TRUE",
                Integer.class, menuItemId);
    }


    private long orderIdOf(long customerId) {
        return jdbcTemplate.queryForObject("SELECT id FROM orders WHERE customer_id = ?", Long.class, customerId);
    }


    /**
     * Another transaction holds the item's row: a NOWAIT lock of it is refused at once (55P03, lock_not_available).
     */
    private void assertRowHeldElsewhere(long menuItemId, String message) {
        DataAccessException refused = Assertions.assertThrows(DataAccessException.class, () -> jdbcTemplate.queryForObject(
                "SELECT id FROM menu_items WHERE id = ? FOR UPDATE NOWAIT", Long.class, menuItemId), message);
        SQLException cause = Assertions.assertInstanceOf(SQLException.class, refused.getMostSpecificCause(), message);
        Assertions.assertEquals("55P03", cause.getSQLState(), message);
    }


    /**
     * The customer's checkout was refused when it took the stock, after the order, its event and the emptied cart were
     * written: its cart is as it was and no idempotency record is left, so with one more in stock the retry with the
     * same key pays.
     */
    private void assertRefusedLeftNothingBehind(long customerId, String fullCart, long shortItem, String inRound) {
        Assertions.assertEquals(fullCart, cart(customerId), inRound);
        Assertions.assertEquals(0, idempotencyRecords(customerId, "pay-" + customerId), inRound);
        limit(shortItem, 1);
        Assertions.assertEquals("PAID", payOutcome(customerId), inRound);
    }


    private static String refused(String menuItemName) {
        return "CONFLICT|" + StockService.OUT_OF_STOCK + "|" + outOfStock(menuItemName);
    }
}
