package com.laioffer.onlineorder.entity;


import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;


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
        Double price,
        @Column("quantity")
        Integer quantity
) {
}
