package com.laioffer.onlineorder.repository;


import com.laioffer.onlineorder.entity.MenuItemEntity;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;


import java.util.List;




public interface MenuItemRepository extends ListCrudRepository<MenuItemEntity, Long> {


    /**
     * Stable order, so the cached menu and its HTTP ETag are the same on every instance. Served by
     * idx_menu_items_restaurant_id_id.
     */
    @Query("SELECT * FROM menu_items WHERE restaurant_id = :restaurantId ORDER BY id")
    List<MenuItemEntity> findByRestaurantIdOrderById(Long restaurantId);

}
