package com.laioffer.onlineorder.repository;


import com.laioffer.onlineorder.entity.OrderEntity;
import org.springframework.data.repository.ListCrudRepository;


import java.util.List;


public interface OrderRepository extends ListCrudRepository<OrderEntity, Long> {

    List<OrderEntity> findAllByCustomerIdOrderByCreatedAtDesc(Long customerId);
}
