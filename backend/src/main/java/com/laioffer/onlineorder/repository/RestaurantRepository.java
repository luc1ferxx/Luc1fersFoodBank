package com.laioffer.onlineorder.repository;


import com.laioffer.onlineorder.entity.RestaurantEntity;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;

import java.util.List;

public interface RestaurantRepository extends ListCrudRepository<RestaurantEntity, Long> {


    /**
     * Stable order, so the cached list and its HTTP ETag are the same on every instance.
     */
    @Query("SELECT * FROM restaurants ORDER BY id")
    List<RestaurantEntity> findAllOrderById();


    /**
     * Partial update in one statement: null arguments keep the current column value, and concurrent partial
     * updates of different fields cannot overwrite each other.
     */
    @Modifying
    @Query("""
            UPDATE restaurants
            SET name = COALESCE(:name, name),
                address = COALESCE(:address, address),
                phone = COALESCE(:phone, phone),
                image_url = COALESCE(:imageUrl, image_url)
            WHERE id = :id
            """)
    int updateBasicFields(Long id, String name, String address, String phone, String imageUrl);
}
