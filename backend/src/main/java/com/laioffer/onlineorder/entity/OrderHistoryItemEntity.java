package com.laioffer.onlineorder.entity;


import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;


import com.laioffer.onlineorder.money.Money;


import java.math.BigDecimal;


@Table("order_history_items")
public record OrderHistoryItemEntity(
        @Id
        @Column("id")
        Long id,
        @Column("order_id")
        Long orderId,
        @Column("menu_item_id")
        Long menuItemId,
        @Column("restaurant_id")
        Long restaurantId,
        @Column("price")
        BigDecimal price,
        @Column("quantity")
        Integer quantity,
        @Column("menu_item_name")
        String menuItemName,
        @Column("menu_item_description")
        String menuItemDescription,
        @Column("menu_item_image_url")
        String menuItemImageUrl,
        @Column("currency")
        String currency
) {


    public Money unitPrice() {
        return Money.of(price, currency);
    }
}
