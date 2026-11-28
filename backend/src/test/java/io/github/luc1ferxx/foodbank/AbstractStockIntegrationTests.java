package io.github.luc1ferxx.foodbank;


import io.github.luc1ferxx.foodbank.audit.AuditActor;
import io.github.luc1ferxx.foodbank.entity.MenuItemEntity;
import io.github.luc1ferxx.foodbank.entity.OrderHistoryItemEntity;
import io.github.luc1ferxx.foodbank.entity.OrderItemEntity;
import io.github.luc1ferxx.foodbank.exception.ConflictException;
import io.github.luc1ferxx.foodbank.fulfilment.order.OrderLifecycleCommands;
import io.github.luc1ferxx.foodbank.model.OrderActor;
import io.github.luc1ferxx.foodbank.model.OrderDto;
import io.github.luc1ferxx.foodbank.model.OrderStatus;
import io.github.luc1ferxx.foodbank.repository.MenuItemRepository;
import io.github.luc1ferxx.foodbank.repository.OrderHistoryItemRepository;
import io.github.luc1ferxx.foodbank.service.CartService;
import io.github.luc1ferxx.foodbank.service.CustomerService;
import io.github.luc1ferxx.foodbank.service.OrderService;
import io.github.luc1ferxx.foodbank.service.StockService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;


import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;


import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;


/**
 * Menu item stock ({@link StockService}, V15) on a real database. A checkout, paid or not, takes the stock of its
 * limited items and marks the order lines that took some; a short item refuses it with 409 OUT_OF_STOCK and leaves
 * nothing behind, so the customer can retry with the same key; every cancel gives back what its order took, once,
 * unless the food was picked up; and only admins read, set and remove a limit, audited when it changed. The cases have
 * to hold on PostgreSQL and on H2 (the {@code h2} profile), so each database gets a subclass; the PostgreSQL one adds
 * the races. Every test creates its own restaurant and items: a limit on a demo item would refuse other tests' orders.
 */
abstract class AbstractStockIntegrationTests {

    static final String ADMIN_EMAIL = "demo@example.com";

    static final String STOCK = "/admin/menu-items/{id}/stock";

    static final String CARD = """
            {"cardholder_name": "Stock Tester", "card_number": "4242424242424242", "expiry": "12/30", "cvv": "123"}
            """;

    /**
     * The order module checks that the order is the restaurant's, not that the merchant has an account.
     */
    static final OrderActor MERCHANT = OrderActor.merchant(40L);

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    CustomerService customerService;

    @Autowired
    CartService cartService;

    @Autowired
    OrderService orderService;

    @Autowired
    OrderLifecycleCommands orderLifecycleCommands;

    /**
     * The real service, which a test can make run a step just before the take ({@link #beforeTheTake}).
     */
    @MockitoSpyBean
    StockService stockService;

    @Autowired
    MenuItemRepository menuItemRepository;

    @Autowired
    OrderHistoryItemRepository orderHistoryItemRepository;

    @Autowired
    PlatformTransactionManager transactionManager;


    @Test
    void paidCheckout_shouldTakeTheStockOfItsLimitedItemsAndMarkTheirLines() throws Exception {
        Customer alice = customer("stock-paid-");
        long restaurant = restaurant();
        long burger = menuItem(restaurant, "Burger", 5);
        long fries = menuItem(restaurant, "Fries", null);
        addToCart(alice.id(), burger, 2);
        addToCart(alice.id(), fries, 3);

        long orderId = orderId(payAndCheckout(alice, "pay-" + alice.id())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID")));

        Assertions.assertEquals(3, stock(burger));
        Assertions.assertNull(stock(fries), "an unlimited item takes no stock");
        Assertions.assertEquals(Map.of(burger, true, fries, false), marks(orderId));
    }


    /**
     * An admin can mark a PLACED order paid, so an unpaid order holds its stock until it is paid or cancelled.
     */
    @Test
    void unpaidCheckout_shouldTakeStockToo_andItsCancelShouldGiveItBack() throws Exception {
        Customer bob = customer("stock-placed-");
        long burger = menuItem(restaurant(), "Burger", 4);
        addToCart(bob.id(), burger, 3);

        long orderId = orderId(mockMvc.perform(post("/cart/checkout")
                        .session(bob.session())
                        .header("Idempotency-Key", "place-" + bob.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PLACED")));
        Assertions.assertEquals(1, stock(burger));
        Assertions.assertEquals(Map.of(burger, true), marks(orderId));

        cancel(bob, orderId, "cancel-" + orderId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        Assertions.assertEquals(4, stock(burger));
        Assertions.assertEquals(Map.of(burger, false), marks(orderId));
    }


    /**
     * The 409 names the item and leaves nothing behind: no order, the cart as it was, the stock, and no idempotency
     * record, so the customer's retry with the same key goes through once the quantity fits.
     */
    @Test
    void checkout_ofMoreThanIsLeft_shouldWriteNothing_andTheRetryWithTheSameKeyShouldGoThrough() throws Exception {
        Customer carol = customer("stock-sold-out-");
        long burger = menuItem(restaurant(), "Stock Burger", 1);
        addToCart(carol.id(), burger, 2);
        String key = "pay-" + carol.id();
        String fullCart = cart(carol.id());

        payAndCheckout(carol, key)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(StockService.OUT_OF_STOCK))
                .andExpect(jsonPath("$.message").value(outOfStock("Stock Burger")));

        Assertions.assertEquals(0, orders(carol.id()));
        Assertions.assertEquals(fullCart, cart(carol.id()));
        Assertions.assertEquals(1, stock(burger));
        Assertions.assertEquals(0, idempotencyRecords(carol.id(), key));

        cartService.updateOrderItemQuantity(
                carol.id(), cartService.getCart(carol.id()).orderItems().get(0).orderItemId(), 1);
        long orderId = orderId(payAndCheckout(carol, key)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID")));

        Assertions.assertEquals(0, stock(burger));
        Assertions.assertEquals(Map.of(burger, true), marks(orderId));
        Assertions.assertEquals(1, orders(carol.id()));
    }


    @Test
    void checkout_withASoldOutSecondItem_shouldTakeNothingOfTheFirst() throws Exception {
        Customer dave = customer("stock-second-sold-out-");
        long restaurant = restaurant();
        long burger = menuItem(restaurant, "Burger", 3);
        long shake = menuItem(restaurant, "Shake", 0);
        addToCart(dave.id(), burger, 1);
        addToCart(dave.id(), shake, 1);

        payAndCheckout(dave, "pay-" + dave.id())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(StockService.OUT_OF_STOCK))
                .andExpect(jsonPath("$.message").value(outOfStock("Shake")));

        Assertions.assertEquals(3, stock(burger));
        Assertions.assertEquals(0, stock(shake));
        Assertions.assertEquals(0, orders(dave.id()));
    }


    /**
     * The take at the end of checkout when an item ran short after the early check (here made so after the order was
     * placed unlimited): the 409 rolls back the take of the items before it with the caller's transaction.
     */
    @Test
    void reserve_whenALaterItemIsShort_shouldRollBackTheTakesBeforeIt() throws Exception {
        Customer erin = customer("stock-late-");
        long restaurant = restaurant();
        long burger = menuItem(restaurant, "Burger", null);
        long shake = menuItem(restaurant, "Shake", null);
        Assertions.assertTrue(burger < shake, "taken in ascending id order, so the burger first");
        addToCart(erin.id(), burger, 1);
        addToCart(erin.id(), shake, 1);
        OrderDto order = cartService.checkout(erin.id(), "PAID");
        limit(burger, 5);
        limit(shake, 0);
        List<OrderItemEntity> lines = order.items().stream()
                .map(item -> new OrderItemEntity(
                        null, item.menuItemId(), null, item.price(), item.quantity(), item.currency()))
                .toList();
        Map<Long, MenuItemEntity> menuItems = Map.of(
                burger, menuItemRepository.findById(burger).orElseThrow(),
                shake, menuItemRepository.findById(shake).orElseThrow());

        ConflictException conflict = Assertions.assertThrows(ConflictException.class,
                () -> new TransactionTemplate(transactionManager).executeWithoutResult(
                        status -> stockService.reserve(order.id(), lines, menuItems)));

        Assertions.assertEquals(StockService.OUT_OF_STOCK, conflict.getCode());
        Assertions.assertEquals(outOfStock("Shake"), conflict.getMessage());
        Assertions.assertEquals(5, stock(burger), "the burger's take rolled back");
        Assertions.assertEquals(0, stock(shake));
        Assertions.assertEquals(Map.of(burger, false, shake, false), marks(order.id()));
    }


    /**
     * The late refusal inside a real checkout: another checkout takes one of the two between the early check and the
     * take ({@link #beforeTheTake}), so the take finds too few. The 409 rolls back everything the checkout wrote by
     * then (the order, its capture, history and event, the emptied cart, the idempotency record), so the retry with
     * the same key goes through once there is enough again.
     */
    @Test
    void checkout_whenTheStockRunsShortBeforeTheTake_shouldWriteNothing_andTheRetryWithTheSameKeyShouldGoThrough()
            throws Exception {
        Customer lena = customer("stock-late-checkout-");
        long burger = menuItem(restaurant(), "Late Burger", 2);
        addToCart(lena.id(), burger, 2);
        String key = "pay-" + lena.id();
        String fullCart = cart(lena.id());
        beforeTheTake(() -> limit(burger, 1));

        payAndCheckout(lena, key)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(StockService.OUT_OF_STOCK))
                .andExpect(jsonPath("$.message").value(outOfStock("Late Burger")));

        Assertions.assertEquals(0, orders(lena.id()));
        Assertions.assertEquals(fullCart, cart(lena.id()));
        Assertions.assertEquals(0, idempotencyRecords(lena.id(), key));
        Assertions.assertEquals(1, stock(burger), "what the other checkout left");

        limit(burger, 2);
        long orderId = orderId(payAndCheckout(lena, key)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID")));

        Assertions.assertEquals(0, stock(burger));
        Assertions.assertEquals(Map.of(burger, true), marks(orderId));
    }


    /**
     * The order that keeps two checkouts or cancels of the same items from deadlocking: the take and the give-back list
     * the limited items in ascending menu item id, whatever order the cart got them in. Here the cart gets the shake
     * (the higher id) first, so its order line has the lower line id.
     */
    @Test
    void theStockQueries_shouldListTheItemsInAscendingId_whateverOrderTheCartGotThem() throws Exception {
        Customer mia = customer("stock-lock-order-");
        long restaurant = restaurant();
        long burger = menuItem(restaurant, "Burger", 5);
        long shake = menuItem(restaurant, "Shake", 5);
        Assertions.assertTrue(burger < shake, "the burger has the lower id");
        addToCart(mia.id(), shake, 1);
        addToCart(mia.id(), burger, 1);

        Assertions.assertEquals(List.of(burger, shake), menuItemRepository.findStockLimitedIds(List.of(shake, burger)));
        OrderDto order = cartService.checkout(mia.id(), "PAID");
        List<OrderHistoryItemEntity> reserved = orderHistoryItemRepository.findStockReservedByOrderId(order.id());

        Assertions.assertEquals(List.of(burger, shake), reserved.stream().map(OrderHistoryItemEntity::menuItemId).toList());
        Assertions.assertTrue(reserved.get(0).id() > reserved.get(1).id(), "the cart got the burger second");
    }


    @Test
    void replay_ofACheckout_shouldReturnItsOrderAndTakeNothingMore() throws Exception {
        Customer frank = customer("stock-replay-");
        long burger = menuItem(restaurant(), "Burger", 5);
        addToCart(frank.id(), burger, 2);
        String key = "pay-" + frank.id();
        long orderId = orderId(payAndCheckout(frank, key).andExpect(status().isOk()));

        long replayed = orderId(payAndCheckout(frank, key).andExpect(status().isOk()));

        Assertions.assertEquals(orderId, replayed);
        Assertions.assertEquals(3, stock(burger));
        Assertions.assertEquals(1, orders(frank.id()));
        Assertions.assertEquals(Map.of(burger, true), marks(orderId));
    }


    /**
     * Every path that cancels an order gives back what the order took, once: the customer's cancel (again with a new
     * key), an admin's, the payment timeout of a PLACED order, the accept timeout and the merchant's reject, each
     * also repeated. The five orders take 1 to 5 of 15.
     */
    @Test
    void everyCancel_shouldGiveBackWhatItsOrderTook_once() throws Exception {
        Customer gina = customer("stock-cancels-");
        long restaurant = restaurant();
        long burger = menuItem(restaurant, "Burger", 15);
        OrderDto byCustomer = order(gina.id(), "PAID", burger, 1);
        OrderDto byAdmin = order(gina.id(), "PAID", burger, 2);
        OrderDto unpaid = order(gina.id(), "PLACED", burger, 3);
        OrderDto unaccepted = order(gina.id(), "PAID", burger, 4);
        OrderDto rejected = order(gina.id(), "PAID", burger, 5);
        Assertions.assertEquals(0, stock(burger));

        cancel(gina, byCustomer.id(), "cancel-" + byCustomer.id()).andExpect(status().isOk());
        Assertions.assertEquals(1, stock(burger), "the customer's cancel");
        cancel(gina, byCustomer.id(), "cancel-again-" + byCustomer.id())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        Assertions.assertEquals(1, stock(burger), "the customer's second cancel, with a new key");

        for (String key : List.of("admin-cancel-", "admin-cancel-again-")) {
            Assertions.assertEquals("CANCELLED", orderService.idempotentTransitionOrderStatus(
                    adminId(), byAdmin.id(), "CANCELLED", key + byAdmin.id()).status());
            Assertions.assertEquals(3, stock(burger), "an admin's cancel, key " + key);
        }

        Assertions.assertTrue(orderLifecycleCommands.cancelIfStillIn(unpaid.id(), OrderStatus.PLACED,
                OrderLifecycleCommands.PAYMENT_TIMEOUT, OrderActor.system()).isPresent());
        Assertions.assertTrue(orderLifecycleCommands.cancelIfStillIn(unpaid.id(), OrderStatus.PLACED,
                OrderLifecycleCommands.PAYMENT_TIMEOUT, OrderActor.system()).isEmpty());
        Assertions.assertEquals(6, stock(burger), "the payment timeout");

        Assertions.assertTrue(orderLifecycleCommands.cancelIfStillIn(unaccepted.id(), OrderStatus.PAID,
                OrderLifecycleCommands.MERCHANT_TIMEOUT, OrderActor.system()).isPresent());
        Assertions.assertTrue(orderLifecycleCommands.cancelIfStillIn(unaccepted.id(), OrderStatus.PAID,
                OrderLifecycleCommands.MERCHANT_TIMEOUT, OrderActor.system()).isEmpty());
        Assertions.assertEquals(10, stock(burger), "the accept timeout");

        String reason = OrderLifecycleCommands.MERCHANT_REJECTED + ": Out of buns";
        Assertions.assertTrue(orderLifecycleCommands.transitionRestaurantOrder(rejected.id(), restaurant,
                OrderStatus.PAID, OrderStatus.CANCELLED, reason, MERCHANT).changed());
        Assertions.assertFalse(orderLifecycleCommands.transitionRestaurantOrder(rejected.id(), restaurant,
                OrderStatus.PAID, OrderStatus.CANCELLED, reason, MERCHANT).changed());
        Assertions.assertEquals(15, stock(burger), "the merchant's reject");

        for (OrderDto order : List.of(byCustomer, byAdmin, unpaid, unaccepted, rejected)) {
            Assertions.assertEquals(Map.of(burger, false), marks(order.id()), "order " + order.id());
        }
    }


    @Test
    void cancel_afterThePickup_shouldGiveNothingBack() throws Exception {
        Customer hana = customer("stock-picked-up-");
        long restaurant = restaurant();
        long burger = menuItem(restaurant, "Burger", 5);
        OrderDto paid = order(hana.id(), "PAID", burger, 2);
        orderLifecycleCommands.transitionRestaurantOrder(
                paid.id(), restaurant, OrderStatus.PAID, OrderStatus.ACCEPTED, null, MERCHANT);
        Assertions.assertTrue(orderLifecycleCommands.recordPickup(paid.id(), LocalDateTime.now(), OrderActor.system()));

        cancel(hana, paid.id(), "cancel-" + paid.id())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        Assertions.assertEquals(3, stock(burger), "picked-up food is gone");
    }


    /**
     * An admin sets an absolute limit, so an older order's cancel adds its quantity on top of it; an item whose limit
     * was removed has nothing to give back to and stays unlimited. Both orders lose their marks.
     */
    @Test
    void cancel_afterTheLimitWasChangedOrRemoved_shouldAddToTheNewLimitOrLeaveTheItemUnlimited() throws Exception {
        Customer iris = customer("stock-limit-changed-");
        long restaurant = restaurant();
        long burger = menuItem(restaurant, "Burger", 5);
        long shake = menuItem(restaurant, "Shake", 5);
        OrderDto burgers = order(iris.id(), "PAID", burger, 2);
        OrderDto shakes = order(iris.id(), "PAID", shake, 2);
        stockService.setStock(burger, 10, AuditActor.admin(adminId()));
        stockService.removeStockLimit(shake, AuditActor.admin(adminId()));

        cancel(iris, burgers.id(), "cancel-" + burgers.id()).andExpect(status().isOk());
        cancel(iris, shakes.id(), "cancel-" + shakes.id()).andExpect(status().isOk());

        Assertions.assertEquals(12, stock(burger));
        Assertions.assertNull(stock(shake));
        Assertions.assertEquals(Map.of(burger, false), marks(burgers.id()));
        Assertions.assertEquals(Map.of(shake, false), marks(shakes.id()));
    }


    @Test
    void cancel_ofAnOrderFromBeforeTheLimit_shouldGiveNothingBack() throws Exception {
        Customer jack = customer("stock-before-limit-");
        long burger = menuItem(restaurant(), "Burger", null);
        OrderDto paid = order(jack.id(), "PAID", burger, 2);
        Assertions.assertEquals(Map.of(burger, false), marks(paid.id()));
        limit(burger, 5);

        cancel(jack, paid.id(), "cancel-" + paid.id()).andExpect(status().isOk());

        Assertions.assertEquals(5, stock(burger));
    }


    @Test
    void theDatabase_shouldRefuseANegativeStock() {
        long burger = menuItem(restaurant(), "Burger", 0);

        Assertions.assertThrows(DataIntegrityViolationException.class, () -> limit(burger, -1));

        Assertions.assertEquals(0, stock(burger));
    }


    /**
     * Each change of a limit is audited with its restaurant and the new limit; a write that changes nothing (the
     * limit the item has, or no limit to remove) is not.
     */
    @Test
    void anAdmin_shouldReadSetAndRemoveTheLimit_auditedWhenItChanged() throws Exception {
        MockHttpSession admin = login(ADMIN_EMAIL, "demo123");
        long restaurant = restaurant();
        long burger = menuItem(restaurant, "Burger", null);

        assertStockBody(mockMvc.perform(get(STOCK, burger).session(admin)), burger, null);
        assertStockBody(putStock(admin, burger, "{\"remaining\": 10}"), burger, 10);
        assertStockBody(mockMvc.perform(get(STOCK, burger).session(admin)), burger, 10);
        Assertions.assertEquals(10, stock(burger));
        assertStockBody(putStock(admin, burger, "{\"remaining\": 10}"), burger, 10);
        Assertions.assertEquals(1, stockAudits(burger).size(), "a PUT of the limit the item has writes no entry");

        assertStockBody(mockMvc.perform(delete(STOCK, burger).session(admin)), burger, null);
        assertStockBody(mockMvc.perform(get(STOCK, burger).session(admin)), burger, null);
        Assertions.assertNull(stock(burger));
        assertStockBody(mockMvc.perform(delete(STOCK, burger).session(admin)), burger, null);

        List<Map<String, Object>> audits = stockAudits(burger);
        Assertions.assertEquals(2, audits.size(), "a DELETE of an unlimited item writes no entry");
        for (Map<String, Object> audit : audits) {
            Assertions.assertEquals("menu_item.stock.update", audit.get("action"));
            Assertions.assertEquals(adminId(), ((Number) audit.get("actor_customer_id")).longValue());
            Assertions.assertEquals("ROLE_ADMIN", audit.get("actor_role"));
        }
        Assertions.assertEquals(
                objectMapper.readTree("{\"restaurant_id\": " + restaurant + ", \"limited\": true, \"remaining\": 10}"),
                objectMapper.readTree((String) audits.get(0).get("details")));
        Assertions.assertEquals(
                objectMapper.readTree("{\"restaurant_id\": " + restaurant + ", \"limited\": false}"),
                objectMapper.readTree((String) audits.get(1).get("details")));
    }


    /**
     * A PUT needs a limit from 0 to 1,000,000 (a missing or misspelled one never removes the limit), an unknown item is
     * the service's 404, and only admins pass the security rule: 401 signed out and 403 for a customer, on every
     * method. None of them changes the limit or writes an audit entry.
     */
    @Test
    void theStockEndpoints_shouldRefuseBadLimitsUnknownItemsAndAnyoneButAnAdmin() throws Exception {
        MockHttpSession admin = login(ADMIN_EMAIL, "demo123");
        Customer kate = customer("stock-not-admin-");
        long burger = menuItem(restaurant(), "Burger", 3);

        for (String body : List.of("{}", "{\"remaining\": -1}", "{\"remaining\": 1000001}", "{\"remainig\": 5}")) {
            putStock(admin, burger, body).andExpect(status().isBadRequest());
        }
        for (HttpMethod method : List.of(HttpMethod.GET, HttpMethod.PUT, HttpMethod.DELETE)) {
            mockMvc.perform(request(method, STOCK, burger)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"remaining\": 9}"))
                    .andExpect(status().isUnauthorized());
            mockMvc.perform(request(method, STOCK, burger)
                            .session(kate.session())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"remaining\": 9}"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(request(method, STOCK, 987_654_321L)
                            .session(admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"remaining\": 9}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Menu item not found"));
        }

        Assertions.assertEquals(3, stock(burger));
        Assertions.assertEquals(List.of(), stockAudits(burger));
    }


    // ---- helpers ----


    record Customer(Long id, MockHttpSession session) {
    }


    /**
     * A new customer, signed in with a MockMvc session.
     */
    Customer customer(String prefix) throws Exception {
        String email = prefix + System.nanoTime() + "@example.com";
        return new Customer(signUp(email), login(email, "demo123"));
    }


    /**
     * Signs up a customer, who gets an empty cart, and returns their id.
     */
    long signUp(String email) {
        customerService.signUp(email, "demo123", "Stock", "Tester");
        return jdbcTemplate.queryForObject("SELECT id FROM customers WHERE email = ?", Long.class, email);
    }


    MockHttpSession login(String email, String password) throws Exception {
        return (MockHttpSession) mockMvc.perform(post("/login")
                        .param("username", email)
                        .param("password", password))
                .andExpect(status().isOk())
                .andReturn()
                .getRequest()
                .getSession(false);
    }


    long adminId() {
        return jdbcTemplate.queryForObject("SELECT id FROM customers WHERE email = ?", Long.class, ADMIN_EMAIL);
    }


    long restaurant() {
        String name = "Stock Test " + System.nanoTime();
        jdbcTemplate.update("INSERT INTO restaurants (name, currency) VALUES (?, 'USD')", name);
        return jdbcTemplate.queryForObject("SELECT id FROM restaurants WHERE name = ?", Long.class, name);
    }


    /**
     * A new item of the restaurant at 4.50 USD with {@code stock} left, or unlimited for null.
     */
    long menuItem(long restaurantId, String name, Integer stock) {
        jdbcTemplate.update("""
                INSERT INTO menu_items (restaurant_id, name, price, currency, stock_remaining)
                VALUES (?, ?, 4.50, 'USD', ?)
                """, restaurantId, name, stock);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM menu_items WHERE restaurant_id = ? AND name = ?", Long.class, restaurantId, name);
    }


    void limit(long menuItemId, int stock) {
        jdbcTemplate.update("UPDATE menu_items SET stock_remaining = ? WHERE id = ?", stock, menuItemId);
    }


    /**
     * What is left of the item, or null when it is unlimited.
     */
    Integer stock(long menuItemId) {
        return jdbcTemplate.queryForObject(
                "SELECT stock_remaining FROM menu_items WHERE id = ?", Integer.class, menuItemId);
    }


    /**
     * The order's lines by menu item id: whether each is marked as having taken stock.
     */
    Map<Long, Boolean> marks(long orderId) {
        return jdbcTemplate.query(
                "SELECT menu_item_id, stock_reserved FROM order_history_items WHERE order_id = ?",
                (row, index) -> Map.entry(row.getLong("menu_item_id"), row.getBoolean("stock_reserved")),
                orderId
        ).stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }


    int orders(long customerId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM orders WHERE customer_id = ?", Integer.class, customerId);
    }


    /**
     * The customer's cart, its total and its lines (menu item id x quantity): what a refused checkout leaves as it was.
     */
    String cart(long customerId) {
        Map<String, Object> cart = jdbcTemplate.queryForMap(
                "SELECT id, total_price, currency FROM carts WHERE customer_id = ?", customerId);
        List<String> lines = jdbcTemplate.query(
                "SELECT menu_item_id, quantity FROM order_items WHERE cart_id = ? ORDER BY menu_item_id",
                (row, index) -> row.getLong("menu_item_id") + "x" + row.getInt("quantity"), cart.get("id"));
        return cart.get("total_price") + " " + cart.get("currency") + " " + lines;
    }


    int idempotencyRecords(long customerId, String key) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM idempotency_requests WHERE customer_id = ? AND idempotency_key = ?",
                Integer.class, customerId, key);
    }


    /**
     * Runs {@code step} in a transaction of its own, committed, the next time a checkout is about to take its stock:
     * what another checkout or an admin can do between the early check and the take.
     */
    void beforeTheTake(Runnable step) {
        TransactionTemplate own = new TransactionTemplate(transactionManager);
        own.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        Mockito.doAnswer(invocation -> {
                    own.executeWithoutResult(status -> step.run());
                    return invocation.callRealMethod();
                })
                .doCallRealMethod()
                .when(AopTestUtils.<StockService>getUltimateTargetObject(stockService))
                .reserve(Mockito.any(), Mockito.any(), Mockito.any());
    }


    void addToCart(long customerId, long menuItemId, int quantity) {
        for (int i = 0; i < quantity; i++) {
            cartService.addMenuItemToCart(customerId, menuItemId);
        }
    }


    /**
     * A checkout through the service of {@code quantity} of the item: PAID records the payment capture, PLACED none.
     */
    OrderDto order(long customerId, String status, long menuItemId, int quantity) {
        addToCart(customerId, menuItemId, quantity);
        OrderDto order = cartService.checkout(customerId, status);
        Assertions.assertEquals(status, order.status());
        return order;
    }


    ResultActions payAndCheckout(Customer customer, String key) throws Exception {
        return mockMvc.perform(post("/payments/checkout")
                .session(customer.session())
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(CARD));
    }


    ResultActions cancel(Customer customer, long orderId, String key) throws Exception {
        return mockMvc.perform(post("/orders/{id}/cancel", orderId)
                .session(customer.session())
                .header("Idempotency-Key", key));
    }


    ResultActions putStock(MockHttpSession session, long menuItemId, String body) throws Exception {
        return mockMvc.perform(put(STOCK, menuItemId)
                .session(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }


    /**
     * The whole 200 body of a stock endpoint: the item's id, and what is left unless the item is unlimited.
     */
    void assertStockBody(ResultActions result, long menuItemId, Integer remaining) throws Exception {
        String expected = "{\"menu_item_id\": " + menuItemId
                + (remaining == null ? "" : ", \"remaining\": " + remaining) + "}";
        Assertions.assertEquals(objectMapper.readTree(expected), json(result.andExpect(status().isOk())));
    }


    List<Map<String, Object>> stockAudits(long menuItemId) {
        return jdbcTemplate.queryForList("""
                SELECT actor_customer_id, actor_role, action, details
                FROM audit_log
                WHERE target_type = 'MENU_ITEM'
                  AND target_id = ?
                ORDER BY id
                """, String.valueOf(menuItemId));
    }


    long orderId(ResultActions result) throws Exception {
        return json(result).get("id").asLong();
    }


    JsonNode json(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }


    static String outOfStock(String menuItemName) {
        return "Not enough " + menuItemName + " left. Lower its quantity or remove it from your cart, then try again.";
    }
}
