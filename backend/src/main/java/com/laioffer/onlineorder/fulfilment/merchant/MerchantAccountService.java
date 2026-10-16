package com.laioffer.onlineorder.fulfilment.merchant;


import com.laioffer.onlineorder.exception.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;


/**
 * The merchant module's account API: binds a login (created by the caller) to a restaurant, and activates or
 * deactivates the binding. Admin provisioning (com.laioffer.onlineorder.staff) and GET /me use it; the module only
 * touches its own merchant_accounts table.
 *
 * <p>Deactivation needs no session invalidation: every merchant request looks the account up again
 * ({@link MerchantOrderService}) and an inactive one is answered with 403. A restaurant counts as staffed while it has
 * at least one active account, which decides whether its paid orders get an accept deadline
 * ({@link MerchantInboxService}).
 */
@Service
public class MerchantAccountService {

    private static final Logger LOGGER = LoggerFactory.getLogger(MerchantAccountService.class);

    private final MerchantAccountRepository merchantAccountRepository;


    public MerchantAccountService(MerchantAccountRepository merchantAccountRepository) {
        this.merchantAccountRepository = merchantAccountRepository;
    }


    /**
     * Binds the login to the restaurant as an active merchant. The login must not be bound yet
     * (uq_merchant_account_customer); the caller has just created it.
     */
    @Transactional
    public MerchantAccountEntity create(Long customerId, Long restaurantId) {
        LocalDateTime now = LocalDateTime.now();
        MerchantAccountEntity account = merchantAccountRepository.save(
                new MerchantAccountEntity(null, customerId, restaurantId, true, now, now));
        LOGGER.info(
                "Merchant account created: merchant_id={}, customer_id={}, restaurant_id={}",
                account.id(),
                customerId,
                restaurantId
        );
        return account;
    }


    /**
     * @throws ResourceNotFoundException when there is no such merchant account
     */
    @Transactional
    public MerchantAccountEntity setActive(Long merchantId, boolean active) {
        LocalDateTime now = LocalDateTime.now();
        if (merchantAccountRepository.updateActive(merchantId, active, now) == 0) {
            throw new ResourceNotFoundException("Merchant not found");
        }
        LOGGER.info("Merchant account {}: merchant_id={}", active ? "activated" : "deactivated", merchantId);
        return merchantAccountRepository.findById(merchantId)
                .orElseThrow(() -> new ResourceNotFoundException("Merchant not found"));
    }


    public List<MerchantAccountEntity> findAll() {
        return merchantAccountRepository.findAllOrderById();
    }


    public Optional<MerchantAccountEntity> findByCustomerId(Long customerId) {
        return customerId == null
                ? Optional.empty()
                : Optional.ofNullable(merchantAccountRepository.findByCustomerId(customerId));
    }
}
