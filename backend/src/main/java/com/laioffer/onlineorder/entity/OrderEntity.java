package com.laioffer.onlineorder.entity;


import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;


import java.math.BigDecimal;
import java.time.LocalDateTime;


/**
 * @param id        the internal key, used by every table and API path that references the order
 * @param orderNo   the public order number shown to customers (V10_1): a Snowflake id that does not reveal how many
 *                  orders exist. Null only on a new order before its first save, which assigns it
 *                  (OrderNumberCallback)
 * @param updatedAt when the order last changed status; its creation time until the first transition
 */
@Table("orders")
public record OrderEntity(
        @Id
        @Column("id")
        Long id,
        @Column("order_no")
        Long orderNo,
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
        String cancelReason,
        @Column("updated_at")
        LocalDateTime updatedAt
) {

    @PersistenceCreator
    public OrderEntity {
    }


    /**
     * An order without an order number yet; the first save assigns one.
     */
    public OrderEntity(
            Long id,
            Long customerId,
            BigDecimal totalPrice,
            String status,
            LocalDateTime createdAt,
            Long restaurantId,
            String cancelReason,
            LocalDateTime updatedAt
    ) {
        this(id, null, customerId, totalPrice, status, createdAt, restaurantId, cancelReason, updatedAt);
    }


    /**
     * An order that has not changed status since it was created ({@code updatedAt} is {@code createdAt}).
     */
    public OrderEntity(
            Long id,
            Long customerId,
            BigDecimal totalPrice,
            String status,
            LocalDateTime createdAt,
            Long restaurantId,
            String cancelReason
    ) {
        this(id, customerId, totalPrice, status, createdAt, restaurantId, cancelReason, createdAt);
    }


    /**
     * An order without a restaurant (placed before single-restaurant carts) and without a cancel reason.
     */
    public OrderEntity(Long id, Long customerId, BigDecimal totalPrice, String status, LocalDateTime createdAt) {
        this(id, customerId, totalPrice, status, createdAt, null, null);
    }


    /**
     * This order in {@code status}, changed at {@code changedAt}. The cancel reason is only replaced for CANCELLED.
     */
    public OrderEntity withStatus(String status, String cancelReason, LocalDateTime changedAt) {
        return new OrderEntity(
                id,
                orderNo,
                customerId,
                totalPrice,
                status,
                createdAt,
                restaurantId,
                cancelReason,
                changedAt
        );
    }


    public OrderEntity withOrderNo(Long orderNo) {
        return new OrderEntity(
                id,
                orderNo,
                customerId,
                totalPrice,
                status,
                createdAt,
                restaurantId,
                cancelReason,
                updatedAt
        );
    }
}
