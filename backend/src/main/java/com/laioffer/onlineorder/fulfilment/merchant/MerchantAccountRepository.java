package com.laioffer.onlineorder.fulfilment.merchant;


import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;


public interface MerchantAccountRepository extends ListCrudRepository<MerchantAccountEntity, Long> {

    @Query("SELECT * FROM merchant_accounts WHERE customer_id = :customerId")
    MerchantAccountEntity findByCustomerId(Long customerId);
}
