package io.github.luc1ferxx.foodbank;


import org.springframework.test.context.ActiveProfiles;


/**
 * Checkout with currencies on the h2 profile (its cart line upsert is a read-then-write, see
 * {@link io.github.luc1ferxx.foodbank.service.H2CartItemQuantityUpdater}).
 */
@ActiveProfiles("h2")
class CurrencyCheckoutH2IntegrationTests extends AbstractCurrencyCheckoutIntegrationTests {
}
