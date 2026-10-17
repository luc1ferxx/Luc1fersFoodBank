package com.laioffer.onlineorder.entity;


import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;


import com.laioffer.onlineorder.money.Money;


import java.math.BigDecimal;


@Table("order_items")
public record OrderItemEntity(
        @Id
        @Column("id")
        Long id,
        @Column("menu_item_id")
        Long menuItemId,
        @Column("cart_id")
        Long cartId,
        @Column("price")
        BigDecimal price,
        @Column("quantity")
        Integer quantity,
        @Column("currency")
        String currency
) {


    /**
     * The price snapshot taken when the item was added to the cart.
     */
    public Money unitPrice() {
        return Money.of(price, currency);
    }


    public Money lineTotal() {
        return unitPrice().times(quantity);
    }
}
