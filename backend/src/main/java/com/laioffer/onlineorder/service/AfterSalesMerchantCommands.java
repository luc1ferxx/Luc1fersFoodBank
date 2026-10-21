package com.laioffer.onlineorder.service;


import com.laioffer.onlineorder.fulfilment.order.OrderActor;
import com.laioffer.onlineorder.fulfilment.order.OrderLifecycleCommands;
import com.laioffer.onlineorder.fulfilment.order.OrderLifecycleCommands.AfterSalesDecision;
import com.laioffer.onlineorder.fulfilment.order.OrderLifecycleCommands.AfterSalesResponse;


/**
 * The merchant's answer to an after-sales request (ADR 0002 sections 7 and 11.3), which {@link OrderService} takes for
 * the merchant module through {@link OrderLifecycleCommands#decideAfterSalesCase}. The after-sales module implements
 * it, so the order module does not depend on that module (it owns the interface, the after-sales module depends on the
 * order module), as with {@link CancellationRefunds}.
 */
public interface AfterSalesMerchantCommands {

    /**
     * Takes the merchant's answer on a case of {@code restaurantId} in the caller's transaction (propagation
     * MANDATORY); the contract, with its errors, is {@link OrderLifecycleCommands#decideAfterSalesCase}'s.
     */
    AfterSalesDecision decide(Long caseId, Long restaurantId, AfterSalesResponse response, OrderActor actor);
}
