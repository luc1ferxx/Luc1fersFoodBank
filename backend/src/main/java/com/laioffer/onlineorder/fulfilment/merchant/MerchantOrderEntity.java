package com.laioffer.onlineorder.fulfilment.merchant;


import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;


import java.math.BigDecimal;
import java.time.LocalDateTime;


/**
 * A row of the merchant order inbox.
 *
 * @param orderNo  the order's public number (V14), from the first order event that carried it (version 2); null while
 *                 only version 1 events reached the row
 * @param items    JSON array of the order lines at payment time, priced in {@code currency}
 * @param currency the ISO 4217 currency of the order, from the order event (USD for events published before orders
 *                 carried one)
 */
@Table("merchant_orders")
public record MerchantOrderEntity(
        @Id
        @Column("id")
        Long id,
        @Column("order_id")
        Long orderId,
        @Column("order_no")
        Long orderNo,
        @Column("restaurant_id")
        Long restaurantId,
        @Column("customer_id")
        Long customerId,
        @Column("status")
        String status,
        @Column("total_price")
        BigDecimal totalPrice,
        @Column("items")
        String items,
        @Column("cancel_reason")
        String cancelReason,
        @Column("paid_at")
        LocalDateTime paidAt,
        @Column("created_at")
        LocalDateTime createdAt,
        @Column("updated_at")
        LocalDateTime updatedAt,
        @Column("currency")
        String currency
) {
}
