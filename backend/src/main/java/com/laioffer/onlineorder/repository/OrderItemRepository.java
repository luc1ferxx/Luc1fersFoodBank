package com.laioffer.onlineorder.repository;


import com.laioffer.onlineorder.entity.OrderItemEntity;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;


import java.math.BigDecimal;
import java.util.List;


public interface OrderItemRepository extends ListCrudRepository<OrderItemEntity, Long> {


    List<OrderItemEntity> getAllByCartId(Long cartId);


    OrderItemEntity findByCartIdAndMenuItemId(Long cartId, Long menuItemId);


    @Modifying
    @Query("""
            INSERT INTO order_items (menu_item_id, cart_id, price, currency, quantity)
            VALUES (:menuItemId, :cartId, :price, :currency, 1)
            ON CONFLICT (cart_id, menu_item_id)
            DO UPDATE SET
                price = EXCLUDED.price,
                currency = EXCLUDED.currency,
                quantity = order_items.quantity + 1
            """)
    void incrementQuantity(Long cartId, Long menuItemId, BigDecimal price, String currency);


    /**
     * Restaurants other than {@code restaurantId} that already have items in the cart: empty, or the one restaurant
     * of a single-restaurant cart (at most one row).
     */
    @Query("""
            SELECT menu_items.restaurant_id
            FROM order_items
            JOIN menu_items ON menu_items.id = order_items.menu_item_id
            WHERE order_items.cart_id = :cartId
              AND menu_items.restaurant_id <> :restaurantId
            LIMIT 1
            """)
    List<Long> findOtherRestaurantIdsInCart(Long cartId, Long restaurantId);


    @Modifying
    @Query("DELETE FROM order_items WHERE id = :orderItemId AND cart_id = :cartId")
    void deleteByIdAndCartId(Long orderItemId, Long cartId);


    @Modifying
    @Query("DELETE FROM order_items WHERE cart_id = :cartId")
    void deleteByCartId(Long cartId);
}
