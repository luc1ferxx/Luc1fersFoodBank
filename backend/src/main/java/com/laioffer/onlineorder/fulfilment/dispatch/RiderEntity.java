package com.laioffer.onlineorder.fulfilment.dispatch;


import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;


import java.time.LocalDateTime;


/**
 * @param customerId the rider's login (ROLE_RIDER); null for riders without one, which only the admin delivery
 *                   endpoints can move
 * @param active     an inactive rider gets 403 on /rider/** and is never assigned
 * @param onDuty     set by the rider; dispatch only assigns riders that are active and on duty
 */
@Table("riders")
public record RiderEntity(
        @Id
        @Column("id")
        Long id,
        @Column("customer_id")
        Long customerId,
        @Column("name")
        String name,
        @Column("phone")
        String phone,
        @Column("active")
        boolean active,
        @Column("on_duty")
        boolean onDuty,
        @Column("active_deliveries")
        Integer activeDeliveries,
        @Column("created_at")
        LocalDateTime createdAt,
        @Column("updated_at")
        LocalDateTime updatedAt
) {
}
