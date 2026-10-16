package com.laioffer.onlineorder.repository;


import com.laioffer.onlineorder.entity.RestaurantEntity;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;

import java.util.List;

public interface RestaurantRepository extends ListCrudRepository<RestaurantEntity, Long> {


    /**
     * Stable order, so the cached list and its HTTP ETag are the same on every instance.
     */
    @Query("SELECT * FROM restaurants ORDER BY id")
    List<RestaurantEntity> findAllOrderById();

}
