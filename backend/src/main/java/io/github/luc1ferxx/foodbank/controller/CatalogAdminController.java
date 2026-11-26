package io.github.luc1ferxx.foodbank.controller;


import io.github.luc1ferxx.foodbank.audit.AuditActorResolver;
import io.github.luc1ferxx.foodbank.model.CatalogMenuItemDto;
import io.github.luc1ferxx.foodbank.model.CatalogRestaurantDto;
import io.github.luc1ferxx.foodbank.model.UpdateMenuItemBody;
import io.github.luc1ferxx.foodbank.model.UpdateRestaurantBody;
import io.github.luc1ferxx.foodbank.service.MenuItemService;
import io.github.luc1ferxx.foodbank.service.RestaurantService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;


import java.security.Principal;


/**
 * Admin catalog edits. Each write commits to the database first and evicts the affected cache entries after
 * the commit (see {@link io.github.luc1ferxx.foodbank.cache.CatalogCacheInvalidator}).
 */
@RestController
public class CatalogAdminController {

    private final MenuItemService menuItemService;
    private final RestaurantService restaurantService;
    private final AuditActorResolver auditActorResolver;


    public CatalogAdminController(
            MenuItemService menuItemService,
            RestaurantService restaurantService,
            AuditActorResolver auditActorResolver
    ) {
        this.menuItemService = menuItemService;
        this.restaurantService = restaurantService;
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


    @PatchMapping("/admin/restaurants/{restaurantId:\\d+}")
    public CatalogRestaurantDto updateRestaurant(
            Principal principal,
            @PathVariable("restaurantId") long restaurantId,
            @Valid @RequestBody UpdateRestaurantBody body
    ) {
        return restaurantService.updateRestaurant(restaurantId, body, auditActorResolver.resolve(principal));
    }
}
