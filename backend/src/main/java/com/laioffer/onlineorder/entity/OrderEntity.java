package com.laioffer.onlineorder.entity;


import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;


import java.time.LocalDateTime;


@Table("orders")
public record OrderEntity(
        @Id
        @Column("id")
        Long id,
        @Column("customer_id")
        Long customerId,
        @Column("total_price")
        Double totalPrice,
        @Column("status")
        String status,
        @Column("created_at")
        LocalDateTime createdAt
) {
}
