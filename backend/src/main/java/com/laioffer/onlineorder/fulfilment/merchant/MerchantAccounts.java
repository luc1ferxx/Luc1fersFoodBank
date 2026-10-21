package com.laioffer.onlineorder.fulfilment.merchant;


import org.springframework.security.access.AccessDeniedException;


/**
 * The account check every merchant request starts with (AGENTS.md "Order fulfilment modules"): the login must be bound
 * to a restaurant and the account active. It reads merchant_accounts on every request, outside any read-only
 * transaction, so a deactivation takes effect on the merchant's next request, whatever the replica lag, even though
 * the session still carries ROLE_MERCHANT.
 */
final class MerchantAccounts {

    private MerchantAccounts() {
    }


    /**
     * @return the restaurant of the merchant's active account
     * @throws AccessDeniedException (403) when the login is not bound to a restaurant or the account is deactivated
     */
    static Long requireActiveRestaurantId(MerchantAccountRepository repository, Long merchantCustomerId) {
        MerchantAccountEntity account = merchantCustomerId == null
                ? null
                : repository.findByCustomerId(merchantCustomerId);
        if (account == null) {
            throw new AccessDeniedException("Merchant account is not bound to a restaurant");
        }
        if (!account.active()) {
            throw new AccessDeniedException("Merchant account is deactivated");
        }
        return account.restaurantId();
    }
}
