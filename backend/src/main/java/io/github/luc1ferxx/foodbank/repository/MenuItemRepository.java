package io.github.luc1ferxx.foodbank.repository;


import io.github.luc1ferxx.foodbank.entity.MenuItemEntity;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;


import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;




public interface MenuItemRepository extends ListCrudRepository<MenuItemEntity, Long> {


    /**
     * Stable order, so the cached menu and its HTTP ETag are the same on every instance. Served by
     * idx_menu_items_restaurant_id_id.
     */
    @Query("SELECT * FROM menu_items WHERE restaurant_id = :restaurantId ORDER BY id")
    List<MenuItemEntity> findByRestaurantIdOrderById(Long restaurantId);


    /**
     * Partial update in one statement: null arguments keep the current column value, and concurrent partial
     * updates of different fields cannot overwrite each other.
     */
    @Modifying
    @Query("""
            UPDATE menu_items
            SET name = COALESCE(:name, name),
                description = COALESCE(:description, description),
                price = COALESCE(:price, price)
            WHERE id = :id
            """)
    int updateCatalogFields(Long id, String name, String description, BigDecimal price);


    /**
     * The items among {@code ids} that have a stock limit (V15), ascending: the order in which checkout takes their row
     * locks, so two checkouts of the same items cannot deadlock. Primary-key lookups, without a lock.
     */
    @Query("SELECT id FROM menu_items WHERE id IN (:ids) AND stock_remaining IS NOT NULL ORDER BY id")
    List<Long> findStockLimitedIds(Collection<Long> ids);


    /**
     * How many of the item can still be ordered: null when it has no limit (or there is no such item). Without a lock,
     * so a checkout reads it again after a take that changed no row.
     */
    @Query("SELECT stock_remaining FROM menu_items WHERE id = :id")
    Integer findStockRemaining(Long id);


    /**
     * Takes {@code quantity} from a limited item's stock unless less is left. Returns 1, or 0 when the item is short,
     * has no limit or is gone. The row lock queues concurrent takes, and PostgreSQL checks the condition again on the
     * row the previous take committed, so the stock never goes below zero (the V15 check is the backstop). The lock
     * (FOR NO KEY UPDATE) does not block the FOR KEY SHARE of a cart line insert: stock_remaining is in no unique key.
     */
    @Modifying
    @Query("""
            UPDATE menu_items
            SET stock_remaining = stock_remaining - :quantity
            WHERE id = :id
              AND stock_remaining >= :quantity
            """)
    int takeStock(Long id, int quantity);


    /**
     * Gives {@code quantity} back to the item's stock, when a cancel releases what its order took. Returns 0 when the
     * item has no limit any more (there is nothing to give back to) or is gone.
     */
    @Modifying
    @Query("""
            UPDATE menu_items
            SET stock_remaining = stock_remaining + :quantity
            WHERE id = :id
              AND stock_remaining IS NOT NULL
            """)
    int giveBackStock(Long id, int quantity);


    /**
     * Sets the item's stock limit to an absolute value. Returns 0 when it already has that limit (or is gone), so the
     * caller audits only a change.
     */
    @Modifying
    @Query("""
            UPDATE menu_items
            SET stock_remaining = :remaining
            WHERE id = :id
              AND (stock_remaining IS NULL OR stock_remaining <> :remaining)
            """)
    int setStockRemaining(Long id, int remaining);


    /**
     * Makes the item unlimited. Returns 0 when it has no limit (or is gone), so the caller audits only a change.
     */
    @Modifying
    @Query("""
            UPDATE menu_items
            SET stock_remaining = NULL
            WHERE id = :id
              AND stock_remaining IS NOT NULL
            """)
    int removeStockLimit(Long id);
}
