package com.laioffer.onlineorder.service;


import com.laioffer.onlineorder.cache.CatalogCacheInvalidator;
import com.laioffer.onlineorder.cache.CatalogCaches;
import com.laioffer.onlineorder.exception.BadRequestException;
import com.laioffer.onlineorder.exception.ResourceNotFoundException;
import com.laioffer.onlineorder.model.CatalogRestaurantDto;
import com.laioffer.onlineorder.model.UpdateRestaurantBody;
import com.laioffer.onlineorder.repository.RestaurantRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


import java.util.List;


@Service
public class RestaurantService {


    private final RestaurantRepository restaurantRepository;
    private final CatalogCacheInvalidator cacheInvalidator;


    public RestaurantService(
            RestaurantRepository restaurantRepository,
            CatalogCacheInvalidator cacheInvalidator) {
        this.restaurantRepository = restaurantRepository;
        this.cacheInvalidator = cacheInvalidator;
    }


    /**
     * The restaurant list without menus. The returned list is shared by every caller that hits the cache and
     * must not be modified.
     */
    @Cacheable(cacheNames = CatalogCaches.RESTAURANTS, key = "'" + CatalogCaches.ALL_RESTAURANTS_KEY + "'", sync = true)
    public List<CatalogRestaurantDto> getRestaurantList() {
        return restaurantRepository.findAllOrderById().stream()
                .map(CatalogRestaurantDto::new)
                .toList();
    }


    /**
     * Uncached primary-key lookup, for ids the cached restaurant list does not know.
     */
    public boolean restaurantExists(long restaurantId) {
        return restaurantRepository.existsById(restaurantId);
    }


    /**
     * Drops the cached restaurant list on every instance, for example when it is missing a restaurant that
     * was created outside the admin API.
     */
    public void evictRestaurantList() {
        cacheInvalidator.evictRestaurantsAfterCommit();
    }


    /**
     * Updates the restaurant's basic fields and evicts the cached restaurant list after the commit.
     */
    @Transactional
    public CatalogRestaurantDto updateRestaurant(long restaurantId, UpdateRestaurantBody body) {
        if (body.hasNoChanges()) {
            throw new BadRequestException("At least one of name, address, phone or image_url is required");
        }
        int updatedRows = restaurantRepository.updateBasicFields(
                restaurantId,
                body.name(),
                body.address(),
                body.phone(),
                body.imageUrl()
        );
        if (updatedRows == 0) {
            throw new ResourceNotFoundException("Restaurant not found");
        }
        cacheInvalidator.evictRestaurantsAfterCommit();
        return restaurantRepository.findById(restaurantId)
                .map(CatalogRestaurantDto::new)
                .orElseThrow(() -> new ResourceNotFoundException("Restaurant not found"));
    }
}
