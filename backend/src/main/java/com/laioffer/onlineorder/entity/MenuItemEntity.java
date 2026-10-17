package com.laioffer.onlineorder.entity;


import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;


import com.laioffer.onlineorder.money.Money;


import java.math.BigDecimal;


@Table("menu_items")
public record MenuItemEntity(
        @Id
        @Column("id")
        Long id,
        @Column("restaurant_id")
        Long restaurantId,
        @Column("name")
        String name,
        @Column("description")
        String description,
        @Column("price")
        BigDecimal price,
        @Column("image_url")
        String imageUrl,
        @Column("currency")
        String currency
) {


    /**
     * The price in the restaurant's currency.
     */
    public Money unitPrice() {
        return Money.of(price, currency);
    }
}
