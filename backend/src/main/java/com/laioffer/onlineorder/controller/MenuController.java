package com.laioffer.onlineorder.controller;


import com.laioffer.onlineorder.model.CatalogMenuItemDto;
import com.laioffer.onlineorder.model.RestaurantDto;
import com.laioffer.onlineorder.service.CatalogService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;


import java.util.List;


@RestController
public class MenuController {


    private final CatalogService catalogService;
    private final CatalogHttpCaching httpCaching;


    public MenuController(CatalogService catalogService, CatalogHttpCaching httpCaching) {
        this.catalogService = catalogService;
        this.httpCaching = httpCaching;
    }


    @GetMapping("/restaurant/{restaurantId}/menu")
    public ResponseEntity<List<CatalogMenuItemDto>> getMenuByRestaurant(@PathVariable("restaurantId") long restaurantId) {
        return httpCaching.publicResponse(catalogService.getMenu(restaurantId));
    }


    @GetMapping("/restaurants/menu")
    public ResponseEntity<List<RestaurantDto>> getMenuForAllRestaurants() {
        return httpCaching.publicResponse(catalogService.getRestaurantsWithMenus());
    }
}
