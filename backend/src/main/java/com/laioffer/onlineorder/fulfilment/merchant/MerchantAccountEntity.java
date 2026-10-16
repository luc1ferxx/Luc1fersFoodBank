package com.laioffer.onlineorder.fulfilment.merchant;


import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;


import java.time.LocalDateTime;


@Table("merchant_accounts")
public record MerchantAccountEntity(
        @Id
        @Column("id")
        Long id,
        @Column("customer_id")
        Long customerId,
        @Column("restaurant_id")
        Long restaurantId,
        @Column("created_at")
        LocalDateTime createdAt
) {
}
