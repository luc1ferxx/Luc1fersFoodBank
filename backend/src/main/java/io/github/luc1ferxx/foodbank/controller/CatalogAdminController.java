package io.github.luc1ferxx.foodbank.controller;


import io.github.luc1ferxx.foodbank.audit.AuditActorResolver;
import io.github.luc1ferxx.foodbank.model.CatalogMenuItemDto;
import io.github.luc1ferxx.foodbank.model.CatalogRestaurantDto;
import io.github.luc1ferxx.foodbank.model.MenuItemStockDto;
import io.github.luc1ferxx.foodbank.model.SetMenuItemStockBody;
import io.github.luc1ferxx.foodbank.model.UpdateMenuItemBody;
import io.github.luc1ferxx.foodbank.model.UpdateRestaurantBody;
import io.github.luc1ferxx.foodbank.service.MenuItemService;
import io.github.luc1ferxx.foodbank.service.RestaurantService;
import io.github.luc1ferxx.foodbank.service.StockService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;


import java.security.Principal;


/**
 * Admin catalog edits. Each write commits to the database first and evicts the affected cache entries after
 * the commit (see {@link io.github.luc1ferxx.foodbank.cache.CatalogCacheInvalidator}). A menu item's stock limit is
 * not in the cached catalog, so its writes evict nothing.
 */
@RestController
public class CatalogAdminController {

    private final MenuItemService menuItemService;
    private final RestaurantService restaurantService;
    private final StockService stockService;
    private final AuditActorResolver auditActorResolver;


    public CatalogAdminController(
            MenuItemService menuItemService,
            RestaurantService restaurantService,
            StockService stockService,
            AuditActorResolver auditActorResolver
    ) {
        this.menuItemService = menuItemService;
        this.restaurantService = restaurantService;
        this.stockService = stockService;
        this.auditActorResolver = auditActorResolver;
    }


    @PatchMapping("/admin/menu-items/{menuItemId:\\d+}")
    public CatalogMenuItemDto updateMenuItem(
            Principal principal,
            @PathVariable("menuItemId") long menuItemId,
            @Valid @RequestBody UpdateMenuItemBody body
    ) {
        return menuItemService.updateMenuItem(menuItemId, body, auditActorResolver.resolve(principal));
    }


    @GetMapping("/admin/menu-items/{menuItemId:\\d+}/stock")
    public MenuItemStockDto getMenuItemStock(@PathVariable("menuItemId") long menuItemId) {
        return stockService.getStock(menuItemId);
    }


    @PutMapping("/admin/menu-items/{menuItemId:\\d+}/stock")
    public MenuItemStockDto setMenuItemStock(
            Principal principal,
            @PathVariable("menuItemId") long menuItemId,
            @Valid @RequestBody SetMenuItemStockBody body
    ) {
        return stockService.setStock(menuItemId, body.remaining(), auditActorResolver.resolve(principal));
    }


    @DeleteMapping("/admin/menu-items/{menuItemId:\\d+}/stock")
    public MenuItemStockDto removeMenuItemStockLimit(
            Principal principal,
            @PathVariable("menuItemId") long menuItemId
    ) {
        return stockService.removeStockLimit(menuItemId, auditActorResolver.resolve(principal));
    }


    @PatchMapping("/admin/restaurants/{restaurantId:\\d+}")
    public CatalogRestaurantDto updateRestaurant(
            Principal principal,
            @PathVariable("restaurantId") long restaurantId,
            @Valid @RequestBody UpdateRestaurantBody body
    ) {
        return restaurantService.updateRestaurant(restaurantId, body, auditActorResolver.resolve(principal));
    }
}
