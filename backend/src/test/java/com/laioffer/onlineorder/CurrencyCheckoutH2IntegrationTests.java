package com.laioffer.onlineorder;


import org.springframework.test.context.ActiveProfiles;


/**
 * Checkout with currencies on the h2 profile (its cart line upsert is a read-then-write, see
 * {@link com.laioffer.onlineorder.service.H2CartItemQuantityUpdater}).
 */
@ActiveProfiles("h2")
class CurrencyCheckoutH2IntegrationTests extends AbstractCurrencyCheckoutIntegrationTests {
}
