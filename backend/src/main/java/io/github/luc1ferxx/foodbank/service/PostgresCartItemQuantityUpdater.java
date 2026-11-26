package io.github.luc1ferxx.foodbank.service;


import io.github.luc1ferxx.foodbank.repository.OrderItemRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;


import io.github.luc1ferxx.foodbank.money.Money;


@Service
@Profile("!h2")
public class PostgresCartItemQuantityUpdater implements CartItemQuantityUpdater {

    private final OrderItemRepository orderItemRepository;


    public PostgresCartItemQuantityUpdater(OrderItemRepository orderItemRepository) {
        this.orderItemRepository = orderItemRepository;
    }


    @Override
    public void incrementQuantity(Long cartId, Long menuItemId, Money unitPrice) {
        orderItemRepository.incrementQuantity(cartId, menuItemId, unitPrice.amount(), unitPrice.currencyCode());
    }
}
