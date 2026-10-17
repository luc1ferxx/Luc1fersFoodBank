package com.laioffer.onlineorder.entity;


import com.laioffer.onlineorder.money.Money;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;


import java.math.BigDecimal;
import java.time.LocalDateTime;


/**
 * @param updatedAt when the order last changed status; its creation time until the first transition
 * @param currency  the ISO 4217 currency of the total and of every item: the currency of the cart it was checked out
 *                  from
 */
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
        String cancelReason,
        @Column("updated_at")
        LocalDateTime updatedAt,
        @Column("currency")
        String currency
) {

    @PersistenceCreator
    public OrderEntity {
    }


    /**
     * An order that has not changed status since it was created ({@code updatedAt} is {@code createdAt}).
     */
    public OrderEntity(
            Long id,
            Long customerId,
            Money total,
            String status,
            LocalDateTime createdAt,
            Long restaurantId,
            String cancelReason
    ) {
        this(id, customerId, total.amount(), status, createdAt, restaurantId, cancelReason, createdAt, total.currencyCode());
    }


    /**
     * An order without a restaurant (placed before single-restaurant carts) and without a cancel reason.
     */
    public OrderEntity(Long id, Long customerId, Money total, String status, LocalDateTime createdAt) {
        this(id, customerId, total, status, createdAt, null, null);
    }


    public Money total() {
        return Money.of(totalPrice, currency);
    }


    /**
     * This order in {@code status}, changed at {@code changedAt}. The cancel reason is only replaced for CANCELLED.
     */
    public OrderEntity withStatus(String status, String cancelReason, LocalDateTime changedAt) {
        return new OrderEntity(
                id,
                customerId,
                totalPrice,
                status,
                createdAt,
                restaurantId,
                cancelReason,
                changedAt,
                currency
        );
    }
}
