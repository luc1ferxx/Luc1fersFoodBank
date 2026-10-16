package com.laioffer.onlineorder.fulfilment.merchant;


import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;


import java.time.LocalDateTime;
import java.util.List;


public interface MerchantAccountRepository extends ListCrudRepository<MerchantAccountEntity, Long> {

    @Query("SELECT * FROM merchant_accounts WHERE customer_id = :customerId")
    MerchantAccountEntity findByCustomerId(Long customerId);


    @Query("SELECT * FROM merchant_accounts WHERE id = :id FOR UPDATE")
    MerchantAccountEntity lockById(Long id);


    @Query("SELECT * FROM merchant_accounts ORDER BY id")
    List<MerchantAccountEntity> findAllOrderById();


    /**
     * The active merchant accounts of a restaurant: a restaurant with none is not staffed. Served by
     * idx_merchant_accounts_restaurant_id.
     */
    @Query("SELECT COUNT(*) FROM merchant_accounts WHERE restaurant_id = :restaurantId AND active = TRUE")
    int countActiveByRestaurantId(Long restaurantId);


    @Modifying
    @Query("""
            UPDATE merchant_accounts
            SET active = :active,
                updated_at = :updatedAt
            WHERE id = :id
            """)
    int updateActive(Long id, boolean active, LocalDateTime updatedAt);
}
