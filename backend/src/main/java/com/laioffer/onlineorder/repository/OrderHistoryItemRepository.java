package com.laioffer.onlineorder.repository;


import com.laioffer.onlineorder.entity.OrderHistoryItemEntity;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;


import java.util.List;


public interface OrderHistoryItemRepository extends ListCrudRepository<OrderHistoryItemEntity, Long> {

    List<OrderHistoryItemEntity> findAllByOrderIdOrderByIdAsc(Long orderId);

    @Query("""
            SELECT *
            FROM order_history_items
            WHERE order_id IN (:orderIds)
            ORDER BY order_id ASC, id ASC
            """)
    List<OrderHistoryItemEntity> findAllByOrderIdInOrderByOrderIdAscIdAsc(List<Long> orderIds);
}
