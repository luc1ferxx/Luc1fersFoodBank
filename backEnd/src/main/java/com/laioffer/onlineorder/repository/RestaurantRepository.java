package com.laioffer.onlineorder.repository;


import com.laioffer.onlineorder.entity.RestaurantEntity;
import org.springframework.data.repository.ListCrudRepository;

import java.util.List;

public interface RestaurantRepository extends ListCrudRepository<RestaurantEntity, Long> {

}
