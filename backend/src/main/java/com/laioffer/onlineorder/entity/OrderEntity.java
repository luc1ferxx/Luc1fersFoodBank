package com.laioffer.onlineorder.entity;


import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;


import java.math.BigDecimal;
import java.time.LocalDateTime;


@Table("orders")
public record OrderEntity(
        @Id
        @Column("id")
        Long id,
        @Column("customer_id")
        Long customerId,
        @Column("total_price")
        BigDecimal totalPrice,
        @Column("status")
        String status,
        @Column("created_at")
        LocalDateTime createdAt,
        @Column("restaurant_id")
        Long restaurantId,
        @Column("cancel_reason")
        String cancelReason
) {

    @PersistenceCreator
    public OrderEntity {
    }


    /**
     * An order without a restaurant (placed before single-restaurant carts) and without a cancel reason.
     */
    public OrderEntity(Long id, Long customerId, BigDecimal totalPrice, String status, LocalDateTime createdAt) {
        this(id, customerId, totalPrice, status, createdAt, null, null);
    }
}
