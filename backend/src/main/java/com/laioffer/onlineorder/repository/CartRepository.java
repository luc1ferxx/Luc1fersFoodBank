package com.laioffer.onlineorder.repository;


import com.laioffer.onlineorder.entity.CartEntity;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;


import java.math.BigDecimal;


public interface CartRepository extends ListCrudRepository<CartEntity, Long> {


    CartEntity getByCustomerId(Long customerId);


    @Query("SELECT * FROM carts WHERE customer_id = :customerId FOR UPDATE")
    CartEntity lockByCustomerId(Long customerId);


    /**
     * Sets the cart's total and currency. The currency can only change while the cart has no lines: every line
     * references its cart's (id, currency).
     */
    @Modifying
    @Query("UPDATE carts SET total_price = :totalPrice, currency = :currency WHERE id = :cartId")
    void updateTotal(Long cartId, BigDecimal totalPrice, String currency);
}
