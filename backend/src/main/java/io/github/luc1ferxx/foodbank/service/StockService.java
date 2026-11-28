package io.github.luc1ferxx.foodbank.service;


import io.github.luc1ferxx.foodbank.audit.AuditActions;
import io.github.luc1ferxx.foodbank.audit.AuditActor;
import io.github.luc1ferxx.foodbank.audit.AuditLogService;
import io.github.luc1ferxx.foodbank.entity.MenuItemEntity;
import io.github.luc1ferxx.foodbank.entity.OrderHistoryItemEntity;
import io.github.luc1ferxx.foodbank.entity.OrderItemEntity;
import io.github.luc1ferxx.foodbank.exception.BadRequestException;
import io.github.luc1ferxx.foodbank.exception.ConflictException;
import io.github.luc1ferxx.foodbank.exception.ResourceNotFoundException;
import io.github.luc1ferxx.foodbank.model.MenuItemStockDto;
import io.github.luc1ferxx.foodbank.repository.MenuItemRepository;
import io.github.luc1ferxx.foodbank.repository.OrderHistoryItemRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;


import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;


/**
 * Menu item stock (V15): an optional limit per item, menu_items.stock_remaining, NULL for unlimited. Customers never
 * see it: no entity or catalog DTO maps the column, so the cached menu is unchanged. Checkout takes its order's stock
 * in the checkout's transaction with a conditional UPDATE, so it never oversells, and marks the order lines that took
 * some (order_history_items.stock_reserved); a cancel gives back exactly the marked lines and clears the marks, so once.
 *
 * <p>Lock order: checkout takes the cart row, the idempotency row, the new order's rows, then the menu_items rows in
 * ascending id; a cancel takes its command's own row (the idempotency row, the merchant_orders row or the
 * order_deadlines row), the order row, the after-sales rows, then the menu_items rows in ascending id. The menu_items
 * rows always come last, and no main code locks one with SELECT ... FOR UPDATE. The stock UPDATE takes FOR NO KEY
 * UPDATE, which does not conflict with the FOR KEY SHARE that a cart line insert takes through its foreign key,
 * because stock_remaining is in no unique key.
 */
@Service
public class StockService {

    public static final String OUT_OF_STOCK = "OUT_OF_STOCK";

    /**
     * The largest limit an admin can set.
     */
    public static final int MAX_STOCK = 1_000_000;

    private final MenuItemRepository menuItemRepository;
    private final OrderHistoryItemRepository orderHistoryItemRepository;
    private final AuditLogService auditLogService;


    public StockService(
            MenuItemRepository menuItemRepository,
            OrderHistoryItemRepository orderHistoryItemRepository,
            AuditLogService auditLogService
    ) {
        this.menuItemRepository = menuItemRepository;
        this.orderHistoryItemRepository = orderHistoryItemRepository;
        this.auditLogService = auditLogService;
    }


    /**
     * The checkout's early check, before it writes anything: a cart line that wants more of a limited item than is left
     * fails it with 409 OUT_OF_STOCK, cheaply. Takes no lock and writes nothing; {@link #reserve} decides.
     *
     * @param menuItemsById the cart's menu items, for the name in the 409
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireInStock(List<OrderItemEntity> cartLines, Map<Long, MenuItemEntity> menuItemsById) {
        Map<Long, Integer> quantities = quantitiesByMenuItemId(cartLines);
        for (Long menuItemId : menuItemRepository.findStockLimitedIds(quantities.keySet())) {
            Integer remaining = menuItemRepository.findStockRemaining(menuItemId);
            if (remaining != null && remaining < quantities.get(menuItemId)) {
                throw outOfStock(menuItemsById.get(menuItemId));
            }
        }
    }


    /**
     * Takes the stock of a new order's limited items and marks the order lines that took it, as the checkout's last
     * step, so the rows stay locked only until its commit. A short item throws 409 OUT_OF_STOCK, and the checkout's
     * transaction rolls back the whole order, the stock already taken included.
     *
     * @param menuItemsById the cart's menu items, for the name in the 409
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reserve(Long orderId, List<OrderItemEntity> cartLines, Map<Long, MenuItemEntity> menuItemsById) {
        Map<Long, Integer> quantities = quantitiesByMenuItemId(cartLines);
        // Read again: a limit may have been set since the early check.
        for (Long menuItemId : menuItemRepository.findStockLimitedIds(quantities.keySet())) {
            if (menuItemRepository.takeStock(menuItemId, quantities.get(menuItemId)) == 1) {
                if (orderHistoryItemRepository.markStockReserved(orderId, menuItemId) != 1) {
                    throw new IllegalStateException(
                            "Order " + orderId + " has no single line of menu item " + menuItemId);
                }
            } else if (menuItemRepository.findStockRemaining(menuItemId) != null) {
                throw outOfStock(menuItemsById.get(menuItemId));
            }
            // Otherwise the limit was removed meanwhile: the item is unlimited and takes nothing.
        }
    }


    /**
     * Gives back the stock a cancelled order took, in ascending menu item id, and clears its marks, in the cancel's
     * transaction: an order is cancelled once (CANCELLED is final), and without marks a second call gives nothing back.
     * A line whose item has lost its limit since gives nothing back.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void release(Long orderId) {
        List<OrderHistoryItemEntity> reserved = orderHistoryItemRepository.findStockReservedByOrderId(orderId);
        if (reserved.isEmpty()) {
            return;
        }
        for (OrderHistoryItemEntity line : reserved) {
            menuItemRepository.giveBackStock(line.menuItemId(), line.quantity());
        }
        orderHistoryItemRepository.clearStockReserved(orderId);
    }


    /**
     * Reads the primary, as checkout does: a read-only transaction would read a replica.
     */
    public MenuItemStockDto getStock(long menuItemId) {
        requireMenuItem(menuItemId);
        return new MenuItemStockDto(menuItemId, menuItemRepository.findStockRemaining(menuItemId));
    }


    /**
     * Sets the item's limit to {@code remaining}, an absolute value: what older orders still hold comes back on top of
     * it when they are cancelled. Audited when it changed the limit.
     */
    @Transactional
    public MenuItemStockDto setStock(long menuItemId, int remaining, AuditActor actor) {
        MenuItemEntity menuItem = requireMenuItem(menuItemId);
        if (remaining < 0 || remaining > MAX_STOCK) {
            throw new BadRequestException("The remaining stock must be between 0 and " + MAX_STOCK);
        }
        if (menuItemRepository.setStockRemaining(menuItemId, remaining) == 1) {
            audit(actor, menuItem, remaining);
        }
        return new MenuItemStockDto(menuItemId, remaining);
    }


    /**
     * Makes the item unlimited. Audited when it had a limit.
     */
    @Transactional
    public MenuItemStockDto removeStockLimit(long menuItemId, AuditActor actor) {
        MenuItemEntity menuItem = requireMenuItem(menuItemId);
        if (menuItemRepository.removeStockLimit(menuItemId) == 1) {
            audit(actor, menuItem, null);
        }
        return new MenuItemStockDto(menuItemId, null);
    }


    /**
     * @param remaining the new limit; null for unlimited, which the entry leaves out
     */
    private void audit(AuditActor actor, MenuItemEntity menuItem, Integer remaining) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("restaurant_id", menuItem.restaurantId());
        details.put("limited", remaining != null);
        details.put("remaining", remaining);
        auditLogService.record(actor, AuditActions.MENU_ITEM_STOCK_UPDATE, AuditActions.TARGET_MENU_ITEM, menuItem.id(),
                details);
    }


    private MenuItemEntity requireMenuItem(long menuItemId) {
        return menuItemRepository.findById(menuItemId)
                .orElseThrow(() -> new ResourceNotFoundException("Menu item not found"));
    }


    /**
     * A cart holds one line per menu item (uk_order_item_cart_menu).
     */
    private static Map<Long, Integer> quantitiesByMenuItemId(List<OrderItemEntity> cartLines) {
        return cartLines.stream().collect(Collectors.toMap(OrderItemEntity::menuItemId, OrderItemEntity::quantity));
    }


    /**
     * The same text early and late, and without a number, which would be stale by the time the customer reads it.
     */
    private static ConflictException outOfStock(MenuItemEntity menuItem) {
        return new ConflictException(OUT_OF_STOCK, "Not enough " + menuItem.name()
                + " left. Lower its quantity or remove it from your cart, then try again.");
    }
}
