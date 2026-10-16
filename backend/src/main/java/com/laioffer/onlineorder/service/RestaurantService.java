package com.laioffer.onlineorder.service;


import com.laioffer.onlineorder.cache.CatalogCaches;
import com.laioffer.onlineorder.model.CatalogRestaurantDto;
import com.laioffer.onlineorder.repository.RestaurantRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;


import java.util.List;


@Service
public class RestaurantService {


    private final RestaurantRepository restaurantRepository;


    public RestaurantService(RestaurantRepository restaurantRepository) {
        this.restaurantRepository = restaurantRepository;
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

}
