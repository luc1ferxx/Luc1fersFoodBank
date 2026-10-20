package com.laioffer.onlineorder.repository;


import com.laioffer.onlineorder.entity.OrderPaymentEntity;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;


import java.util.Optional;


public interface OrderPaymentRepository extends ListCrudRepository<OrderPaymentEntity, Long> {

    @Query("""
            SELECT *
            FROM order_payments
            WHERE order_id = :orderId
            """)
    Optional<OrderPaymentEntity> findByOrderId(Long orderId);
}
