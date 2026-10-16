package com.laioffer.onlineorder.service;


import com.laioffer.onlineorder.exception.ResourceNotFoundException;
import com.laioffer.onlineorder.model.CatalogMenuItemDto;
import com.laioffer.onlineorder.model.CatalogRestaurantDto;
import com.laioffer.onlineorder.model.MenuItemDto;
import com.laioffer.onlineorder.model.RestaurantDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;


import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;


/**
 * Public catalog reads, composed from the cached restaurant list and the per-restaurant menu caches. The
 * cached methods live in {@link RestaurantService} and {@link MenuItemService} so these calls go through the
 * cache proxies.
 */
@Service
public class CatalogService {

    private final RestaurantService restaurantService;
    private final MenuItemService menuItemService;
    private final long unknownRestaurantRecheckNanos;
    private final AtomicLong nextUnknownRestaurantRecheck = new AtomicLong(System.nanoTime());


    public CatalogService(
            RestaurantService restaurantService,
            MenuItemService menuItemService,
            @Value("${app.catalog.unknown-restaurant-recheck-interval:1s}") Duration unknownRestaurantRecheckInterval
    ) {
        this.restaurantService = restaurantService;
        this.menuItemService = menuItemService;
        this.unknownRestaurantRecheckNanos = Math.max(0, unknownRestaurantRecheckInterval.toNanos());
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
     * that do not exist cannot be used to hammer the database (cache penetration), nor fill the menu cache
     * with empty entries.
     * <p>
     * The cached list can lag behind a restaurant created outside the admin API (SQL, a seed run) until it
     * expires. An id the list does not know is therefore looked up in the database by primary key, at most
     * once per {@code app.catalog.unknown-restaurant-recheck-interval} on this instance; other unknown ids in
     * that interval get 404 from the cache. If the restaurant exists, the stale list is evicted everywhere.
     */
    public List<CatalogMenuItemDto> getMenu(long restaurantId) {
        if (!isKnownRestaurant(restaurantId) && !existsButIsMissingFromTheCachedList(restaurantId)) {
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


    private boolean existsButIsMissingFromTheCachedList(long restaurantId) {
        if (!tryStartUnknownRestaurantRecheck() || !restaurantService.restaurantExists(restaurantId)) {
            return false;
        }
        restaurantService.evictRestaurantList();
        return true;
    }


    /**
     * Lets one caller per interval through; concurrent callers that lose the race get false, which makes the
     * recheck single-flight as well.
     */
    private boolean tryStartUnknownRestaurantRecheck() {
        if (unknownRestaurantRecheckNanos == 0) {
            return true;
        }
        long now = System.nanoTime();
        long next = nextUnknownRestaurantRecheck.get();
        return now - next >= 0 && nextUnknownRestaurantRecheck.compareAndSet(next, now + unknownRestaurantRecheckNanos);
    }
}
