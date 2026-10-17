package com.laioffer.onlineorder.entity;


import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;


import com.laioffer.onlineorder.money.Money;


import java.math.BigDecimal;


/**
 * @param currency the ISO 4217 currency of the cart's lines and total; an empty cart keeps the currency it last had
 */
@Table("carts")
public record CartEntity(
        @Id
        @Column("id")
        Long id,
        @Column("customer_id")
        Long customerId,
        @Column("total_price")
        BigDecimal totalPrice,
        @Column("currency")
        String currency
) {


    public Money total() {
        return Money.of(totalPrice, currency);
    }
}
