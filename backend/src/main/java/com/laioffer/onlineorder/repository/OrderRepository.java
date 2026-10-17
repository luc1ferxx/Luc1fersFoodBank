package com.laioffer.onlineorder.repository;


import com.laioffer.onlineorder.entity.OrderEntity;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;


import java.util.List;
import java.util.Optional;


public interface OrderRepository extends ListCrudRepository<OrderEntity, Long> {

    List<OrderEntity> findAllByCustomerIdOrderByCreatedAtDesc(Long customerId);

    @Query("""
            SELECT *
            FROM orders
            WHERE customer_id = :customerId
            ORDER BY created_at DESC, id DESC
            LIMIT :limit
            """)
    List<OrderEntity> findPageByCustomerIdOrderByCreatedAtDesc(Long customerId, int limit);

    Optional<OrderEntity> findByIdAndCustomerId(Long id, Long customerId);

    /**
     * @return the public order number of the order; empty when there is no such order
     */
    @Query("""
            SELECT order_no
            FROM orders
            WHERE id = :id
            """)
    Optional<Long> findOrderNoById(Long id);

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
