package com.laioffer.onlineorder.controller;


import com.laioffer.onlineorder.model.CatalogMenuItemDto;
import com.laioffer.onlineorder.model.RestaurantDto;
import com.laioffer.onlineorder.service.CatalogService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;


import java.util.List;


@RestController
public class MenuController {


    private final CatalogService catalogService;


    public MenuController(CatalogService catalogService) {
        this.catalogService = catalogService;
    }


    @GetMapping("/restaurant/{restaurantId}/menu")
    public List<CatalogMenuItemDto> getMenuByRestaurant(@PathVariable("restaurantId") long restaurantId) {
        return catalogService.getMenu(restaurantId);
    }


    @GetMapping("/restaurants/menu")
    public List<RestaurantDto> getMenuForAllRestaurants() {
        return catalogService.getRestaurantsWithMenus();
    }
}
