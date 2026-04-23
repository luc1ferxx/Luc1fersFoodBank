package com.laioffer.onlineorder.repository;


import com.laioffer.onlineorder.entity.OrderNotificationEntity;
import org.springframework.data.repository.ListCrudRepository;


import java.util.List;


public interface OrderNotificationRepository extends ListCrudRepository<OrderNotificationEntity, Long> {

    List<OrderNotificationEntity> findAllByCustomerIdOrderByCreatedAtDesc(Long customerId);
}
