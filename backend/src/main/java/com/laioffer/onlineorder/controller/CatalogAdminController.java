package com.laioffer.onlineorder.controller;


import com.laioffer.onlineorder.audit.AuditActorResolver;
import com.laioffer.onlineorder.model.CatalogMenuItemDto;
import com.laioffer.onlineorder.model.CatalogRestaurantDto;
import com.laioffer.onlineorder.model.UpdateMenuItemBody;
import com.laioffer.onlineorder.model.UpdateRestaurantBody;
import com.laioffer.onlineorder.service.MenuItemService;
import com.laioffer.onlineorder.service.RestaurantService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;


import java.security.Principal;


/**
 * Admin catalog edits. Each write commits to the database first and evicts the affected cache entries after
 * the commit (see {@link com.laioffer.onlineorder.cache.CatalogCacheInvalidator}).
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
