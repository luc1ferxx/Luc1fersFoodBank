package com.laioffer.onlineorder.service;


import com.laioffer.onlineorder.audit.AuditActions;
import com.laioffer.onlineorder.audit.AuditActor;
import com.laioffer.onlineorder.audit.AuditLogService;
import com.laioffer.onlineorder.cache.CatalogCacheInvalidator;
import com.laioffer.onlineorder.cache.CatalogCaches;
import com.laioffer.onlineorder.datasource.ReadOnlyTransactions;
import com.laioffer.onlineorder.entity.MenuItemEntity;
import com.laioffer.onlineorder.exception.BadRequestException;
import com.laioffer.onlineorder.exception.ResourceNotFoundException;
import com.laioffer.onlineorder.model.CatalogMenuItemDto;
import com.laioffer.onlineorder.model.UpdateMenuItemBody;
import com.laioffer.onlineorder.money.Money;
import com.laioffer.onlineorder.repository.MenuItemRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


@Service
public class MenuItemService {

    public static final String CURRENCY_MISMATCH = "CURRENCY_MISMATCH";
    public static final String INVALID_AMOUNT = "INVALID_AMOUNT";


    private final MenuItemRepository menuItemRepository;
    private final CatalogCacheInvalidator cacheInvalidator;
    private final AuditLogService auditLogService;
    private final ReadOnlyTransactions readOnlyTransactions;


    public MenuItemService(
            MenuItemRepository menuItemRepository,
            CatalogCacheInvalidator cacheInvalidator,
            AuditLogService auditLogService,
            ReadOnlyTransactions readOnlyTransactions
    ) {
        this.menuItemRepository = menuItemRepository;
        this.cacheInvalidator = cacheInvalidator;
        this.auditLogService = auditLogService;
        this.readOnlyTransactions = readOnlyTransactions;
    }


    /**
     * One restaurant's menu, cached under {@code menu::<restaurantId>}. Callers must check that the restaurant
     * exists first (see {@link CatalogService#getMenu(long)}) so unknown ids never reach the database or the
     * cache. The returned list is shared by every caller that hits the cache and must not be modified. A cache miss
     * loads it in a read-only transaction (a replica read with replicas configured); a hit opens none.
     */
    @Cacheable(cacheNames = CatalogCaches.MENU, key = "#restaurantId", sync = true)
    public List<CatalogMenuItemDto> getMenu(long restaurantId) {
        return readOnlyTransactions.read("MenuItemService.getMenu", () ->
                menuItemRepository.findByRestaurantIdOrderById(restaurantId).stream()
                        .map(CatalogMenuItemDto::new)
                        .toList());
    }


    /**
     * Updates a menu item and evicts its restaurant's cached menu after the commit. Carts are not repriced:
     * each cart line keeps the price snapshot taken when the item was added, and checkout charges that snapshot.
     */
    @Transactional
    public CatalogMenuItemDto updateMenuItem(long menuItemId, UpdateMenuItemBody body) {
        return updateMenuItem(menuItemId, body, AuditActor.system());
    }


    /**
     * {@link #updateMenuItem(long, UpdateMenuItemBody)} recorded in the audit log as done by {@code actor}, in the
     * same transaction.
     */
    @Transactional
    public CatalogMenuItemDto updateMenuItem(long menuItemId, UpdateMenuItemBody body, AuditActor actor) {
        if (body.hasNoChanges()) {
            throw new BadRequestException("At least one of name, description or price is required");
        }
        Money newPrice = body.price() == null ? null : priceInRestaurantCurrency(menuItemId, body);
        if (newPrice == null && body.currency() != null) {
            throw new BadRequestException("A currency is only accepted together with a price");
        }
        int updatedRows = menuItemRepository.updateCatalogFields(
                menuItemId,
                body.name(),
                body.description(),
                newPrice == null ? null : newPrice.amount()
        );
        if (updatedRows == 0) {
            throw new ResourceNotFoundException("Menu item not found");
        }
        MenuItemEntity menuItem = menuItemRepository.findById(menuItemId)
                .orElseThrow(() -> new ResourceNotFoundException("Menu item not found"));
        cacheInvalidator.evictMenuAfterCommit(menuItem.restaurantId());

        // The fields the request set (omitted fields were left unchanged).
        Map<String, Object> changes = new LinkedHashMap<>();
        putIfPresent(changes, "name", body.name());
        putIfPresent(changes, "description", body.description());
        if (newPrice != null) {
            changes.put("price", newPrice.amount());
            changes.put("currency", newPrice.currencyCode());
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("restaurant_id", menuItem.restaurantId());
        details.put("changes", changes);
        auditLogService.record(actor, AuditActions.MENU_ITEM_UPDATE, AuditActions.TARGET_MENU_ITEM, menuItemId, details);
        return new CatalogMenuItemDto(menuItem);
    }


    /**
     * The requested price as an amount in the item's currency, which is its restaurant's (the database ties the
     * two together). A price in another currency is rejected rather than converted, and so is one with more decimal
     * places than the currency has (cents for JPY).
     */
    private Money priceInRestaurantCurrency(long menuItemId, UpdateMenuItemBody body) {
        MenuItemEntity menuItem = menuItemRepository.findById(menuItemId)
                .orElseThrow(() -> new ResourceNotFoundException("Menu item not found"));
        String currency = body.currency() == null ? menuItem.currency() : body.currency();
        if (!currency.equals(menuItem.currency())) {
            throw new BadRequestException(
                    CURRENCY_MISMATCH,
                    "Menu item " + menuItemId + " is priced in " + menuItem.currency()
                            + ", the currency of its restaurant; send the price in " + menuItem.currency()
            );
        }
        try {
            return Money.of(body.price(), currency);
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException(INVALID_AMOUNT, ex.getMessage());
        }
    }


    private static void putIfPresent(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}
