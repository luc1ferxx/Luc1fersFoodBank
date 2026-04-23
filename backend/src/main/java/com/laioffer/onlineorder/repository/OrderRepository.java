package com.laioffer.onlineorder.repository;


import com.laioffer.onlineorder.entity.OrderEntity;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;


import java.util.List;
import java.util.Optional;


public interface OrderRepository extends ListCrudRepository<OrderEntity, Long> {

    List<OrderEntity> findAllByCustomerIdOrderByCreatedAtDesc(Long customerId);

    Optional<OrderEntity> findByIdAndCustomerId(Long id, Long customerId);

    @Query("""
            SELECT *
            FROM orders
            WHERE id = :id
            FOR UPDATE
            """)
    OrderEntity lockById(Long id);

    @Query("""
            SELECT *
            FROM orders
            WHERE id = :id
              AND customer_id = :customerId
            FOR UPDATE
            """)
    OrderEntity lockByIdAndCustomerId(Long id, Long customerId);
}
