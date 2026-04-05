package com.laioffer.onlineorder.repository;


import com.laioffer.onlineorder.entity.OrderHistoryItemEntity;
import org.springframework.data.repository.ListCrudRepository;


import java.util.List;


public interface OrderHistoryItemRepository extends ListCrudRepository<OrderHistoryItemEntity, Long> {

    List<OrderHistoryItemEntity> findAllByOrderIdOrderByIdAsc(Long orderId);
}
