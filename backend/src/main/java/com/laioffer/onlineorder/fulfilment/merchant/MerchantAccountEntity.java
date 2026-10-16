package com.laioffer.onlineorder.fulfilment.merchant;


import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;


import java.time.LocalDateTime;


/**
 * A merchant login (a customers row with ROLE_MERCHANT) bound to exactly one restaurant.
 *
 * @param active an inactive merchant gets 403 on every merchant endpoint, and does not make its restaurant staffed
 */
@Table("merchant_accounts")
public record MerchantAccountEntity(
        @Id
        @Column("id")
        Long id,
        @Column("customer_id")
        Long customerId,
        @Column("restaurant_id")
        Long restaurantId,
        @Column("active")
        boolean active,
        @Column("created_at")
        LocalDateTime createdAt,
        @Column("updated_at")
        LocalDateTime updatedAt
) {
}
