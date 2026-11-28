package io.github.luc1ferxx.foodbank.repository;


import io.github.luc1ferxx.foodbank.entity.OrderHistoryItemEntity;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;


import java.util.List;


public interface OrderHistoryItemRepository extends ListCrudRepository<OrderHistoryItemEntity, Long> {

    List<OrderHistoryItemEntity> findAllByOrderIdOrderByIdAsc(Long orderId);

    @Query("""
            SELECT *
            FROM order_history_items
            WHERE order_id IN (:orderIds)
            ORDER BY order_id ASC, id ASC
            """)
    List<OrderHistoryItemEntity> findAllByOrderIdInOrderByOrderIdAscIdAsc(List<Long> orderIds);


    /**
     * Marks the order's line of the menu item as one that took stock (V15), in the checkout's transaction. A cart
     * holds one line per menu item (uk_order_item_cart_menu), so it changes one row. Served by
     * idx_order_history_items_order_id_id.
     */
    @Modifying
    @Query("""
            UPDATE order_history_items
            SET stock_reserved = TRUE
            WHERE order_id = :orderId
              AND menu_item_id = :menuItemId
            """)
    int markStockReserved(Long orderId, Long menuItemId);


    /**
     * The order's lines that took stock, by menu item id: the order in which a cancel takes the menu items' row locks,
     * the same as checkout's. Read under the order's row lock. Served by idx_order_history_items_order_id_id.
     */
    @Query("""
            SELECT *
            FROM order_history_items
            WHERE order_id = :orderId
              AND stock_reserved = TRUE
            ORDER BY menu_item_id
            """)
    List<OrderHistoryItemEntity> findStockReservedByOrderId(Long orderId);


    /**
     * Clears the order's marks once its stock is given back, so it is given back once. Served by
     * idx_order_history_items_order_id_id.
     */
    @Modifying
    @Query("""
            UPDATE order_history_items
            SET stock_reserved = FALSE
            WHERE order_id = :orderId
              AND stock_reserved = TRUE
            """)
    int clearStockReserved(Long orderId);
}
