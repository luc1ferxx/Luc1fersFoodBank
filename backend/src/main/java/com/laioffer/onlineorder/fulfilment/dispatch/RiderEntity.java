package com.laioffer.onlineorder.fulfilment.dispatch;


import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;


import java.time.LocalDateTime;


@Table("riders")
public record RiderEntity(
        @Id
        @Column("id")
        Long id,
        @Column("name")
        String name,
        @Column("phone")
        String phone,
        @Column("active")
        boolean active,
        @Column("active_deliveries")
        Integer activeDeliveries,
        @Column("created_at")
        LocalDateTime createdAt,
        @Column("updated_at")
        LocalDateTime updatedAt
) {
}
