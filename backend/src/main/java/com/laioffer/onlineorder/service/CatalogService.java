package com.laioffer.onlineorder.service;


import com.laioffer.onlineorder.exception.ResourceNotFoundException;
import com.laioffer.onlineorder.model.CatalogMenuItemDto;
import com.laioffer.onlineorder.model.CatalogRestaurantDto;
import com.laioffer.onlineorder.model.MenuItemDto;
import com.laioffer.onlineorder.model.RestaurantDto;
import org.springframework.stereotype.Service;


import java.util.ArrayList;
import java.util.List;


/**
 * Public catalog reads, composed from the cached restaurant list and the per-restaurant menu caches. The
 * cached methods live in {@link RestaurantService} and {@link MenuItemService} so these calls go through the
 * cache proxies.
 */
@Service
public class CatalogService {

    private final RestaurantService restaurantService;
    private final MenuItemService menuItemService;


    public CatalogService(RestaurantService restaurantService, MenuItemService menuItemService) {
        this.restaurantService = restaurantService;
        this.menuItemService = menuItemService;
    }


    /**
     * Every restaurant with its menu, in the response shape of {@code GET /restaurants/menu}: a restaurant
     * without menu items has no {@code menu_items} field, as before the per-restaurant caches existed.
     */
    public List<RestaurantDto> getRestaurantsWithMenus() {
        List<CatalogRestaurantDto> restaurants = restaurantService.getRestaurantList();
        List<RestaurantDto> result = new ArrayList<>(restaurants.size());
        for (CatalogRestaurantDto restaurant : restaurants) {
            List<CatalogMenuItemDto> menu = menuItemService.getMenu(restaurant.id());
            List<MenuItemDto> menuItems = menu.isEmpty() ? null : menu.stream().map(MenuItemDto::new).toList();
            result.add(new RestaurantDto(restaurant, menuItems));
        }
        return result;
    }


    /**
     * One restaurant's menu. The id is first checked against the cached restaurant list, so requests for ids
     * that do not exist are answered with 404 from cache and cannot be used to hammer the database (cache
     * penetration), nor fill the menu cache with empty entries.
     */
    public List<CatalogMenuItemDto> getMenu(long restaurantId) {
        if (!isKnownRestaurant(restaurantId)) {
            throw new ResourceNotFoundException("Restaurant not found");
        }
        return menuItemService.getMenu(restaurantId);
    }


    private boolean isKnownRestaurant(long restaurantId) {
        for (CatalogRestaurantDto restaurant : restaurantService.getRestaurantList()) {
            if (restaurant.id() != null && restaurant.id() == restaurantId) {
                return true;
            }
        }
        return false;
    }
}
