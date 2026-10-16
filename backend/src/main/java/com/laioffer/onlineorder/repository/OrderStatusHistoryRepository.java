package com.laioffer.onlineorder.repository;


import com.laioffer.onlineorder.entity.OrderStatusHistoryEntity;
import org.springframework.data.repository.ListCrudRepository;


import java.util.List;


public interface OrderStatusHistoryRepository extends ListCrudRepository<OrderStatusHistoryEntity, Long> {

    List<OrderStatusHistoryEntity> findAllByOrderIdOrderByIdAsc(Long orderId);
}
