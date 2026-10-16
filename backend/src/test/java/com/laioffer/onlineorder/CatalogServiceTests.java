package com.laioffer.onlineorder;


import com.laioffer.onlineorder.exception.ResourceNotFoundException;
import com.laioffer.onlineorder.model.CatalogMenuItemDto;
import com.laioffer.onlineorder.model.CatalogRestaurantDto;
import com.laioffer.onlineorder.model.MenuItemDto;
import com.laioffer.onlineorder.model.RestaurantDto;
import com.laioffer.onlineorder.service.CatalogService;
import com.laioffer.onlineorder.service.MenuItemService;
import com.laioffer.onlineorder.service.RestaurantService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;


import java.math.BigDecimal;
import java.util.List;


class CatalogServiceTests {

    private RestaurantService restaurantService;
    private MenuItemService menuItemService;
    private CatalogService catalogService;


    @BeforeEach
    void setUp() {
        restaurantService = Mockito.mock(RestaurantService.class);
        menuItemService = Mockito.mock(MenuItemService.class);
        catalogService = new CatalogService(restaurantService, menuItemService);
        Mockito.when(restaurantService.getRestaurantList()).thenReturn(List.of(
                new CatalogRestaurantDto(1L, "Burger King", "773 N Mathilda Ave", "(408) 736-0101", "bk.png"),
                new CatalogRestaurantDto(2L, "Empty Kitchen", null, null, null)
        ));
        Mockito.when(menuItemService.getMenu(1L)).thenReturn(List.of(
                new CatalogMenuItemDto(10L, 1L, "Whopper", "Flame grilled", new BigDecimal("6.39"), "w.png")
        ));
        Mockito.when(menuItemService.getMenu(2L)).thenReturn(List.of());
    }


    @Test
    void getMenu_forUnknownRestaurant_shouldReturnNotFoundWithoutLoadingAMenu() {
        Assertions.assertThrows(ResourceNotFoundException.class, () -> catalogService.getMenu(999L));

        Mockito.verify(menuItemService, Mockito.never()).getMenu(Mockito.anyLong());
    }


    @Test
    void getMenu_forKnownRestaurant_shouldReturnItsCachedMenu() {
        List<CatalogMenuItemDto> menu = catalogService.getMenu(1L);

        Assertions.assertEquals(1, menu.size());
        Assertions.assertEquals(1L, menu.get(0).restaurantId());
    }


    @Test
    void getRestaurantsWithMenus_shouldComposePerRestaurantMenusInTheExistingResponseShape() {
        List<RestaurantDto> restaurants = catalogService.getRestaurantsWithMenus();

        Assertions.assertEquals(List.of(
                new RestaurantDto(1L, "Burger King", "773 N Mathilda Ave", "(408) 736-0101", "bk.png", List.of(
                        new MenuItemDto(10L, "Whopper", "Flame grilled", new BigDecimal("6.39"), "w.png")
                )),
                // No menu items: menu_items stays absent from the JSON, as before.
                new RestaurantDto(2L, "Empty Kitchen", null, null, null, null)
        ), restaurants);
    }
}
