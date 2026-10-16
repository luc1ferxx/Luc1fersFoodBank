package com.laioffer.onlineorder.service;


import com.laioffer.onlineorder.cache.CatalogCaches;
import com.laioffer.onlineorder.model.CatalogMenuItemDto;
import com.laioffer.onlineorder.repository.MenuItemRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;


import java.util.List;


@Service
public class MenuItemService {


    private final MenuItemRepository menuItemRepository;


    public MenuItemService(MenuItemRepository menuItemRepository) {
        this.menuItemRepository = menuItemRepository;
    }


    /**
     * One restaurant's menu, cached under {@code menu::<restaurantId>}. Callers must check that the restaurant
     * exists first (see {@link CatalogService#getMenu(long)}) so unknown ids never reach the database or the
     * cache. The returned list is shared by every caller that hits the cache and must not be modified.
     */
    @Cacheable(cacheNames = CatalogCaches.MENU, key = "#restaurantId", sync = true)
    public List<CatalogMenuItemDto> getMenu(long restaurantId) {
        return menuItemRepository.findByRestaurantIdOrderById(restaurantId).stream()
                .map(CatalogMenuItemDto::new)
                .toList();
    }

}
