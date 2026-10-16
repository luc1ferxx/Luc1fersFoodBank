package com.laioffer.onlineorder.service;


import com.laioffer.onlineorder.cache.CatalogCacheInvalidator;
import com.laioffer.onlineorder.cache.CatalogCaches;
import com.laioffer.onlineorder.entity.MenuItemEntity;
import com.laioffer.onlineorder.exception.BadRequestException;
import com.laioffer.onlineorder.exception.ResourceNotFoundException;
import com.laioffer.onlineorder.model.CatalogMenuItemDto;
import com.laioffer.onlineorder.model.UpdateMenuItemBody;
import com.laioffer.onlineorder.repository.MenuItemRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


import java.util.List;


@Service
public class MenuItemService {


    private final MenuItemRepository menuItemRepository;
    private final CatalogCacheInvalidator cacheInvalidator;


    public MenuItemService(MenuItemRepository menuItemRepository, CatalogCacheInvalidator cacheInvalidator) {
        this.menuItemRepository = menuItemRepository;
        this.cacheInvalidator = cacheInvalidator;
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


    /**
     * Updates a menu item and evicts its restaurant's cached menu after the commit. Carts are not repriced:
     * each cart line keeps the price snapshot taken when the item was added, and checkout charges that snapshot.
     */
    @Transactional
    public CatalogMenuItemDto updateMenuItem(long menuItemId, UpdateMenuItemBody body) {
        if (body.hasNoChanges()) {
            throw new BadRequestException("At least one of name, description or price is required");
        }
        int updatedRows = menuItemRepository.updateCatalogFields(menuItemId, body.name(), body.description(), body.price());
        if (updatedRows == 0) {
            throw new ResourceNotFoundException("Menu item not found");
        }
        MenuItemEntity menuItem = menuItemRepository.findById(menuItemId)
                .orElseThrow(() -> new ResourceNotFoundException("Menu item not found"));
        cacheInvalidator.evictMenuAfterCommit(menuItem.restaurantId());
        return new CatalogMenuItemDto(menuItem);
    }
}
