package com.laioffer.onlineorder.service;


import com.laioffer.onlineorder.repository.OrderItemRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;


@Service
@Profile("!h2")
public class PostgresCartItemQuantityUpdater implements CartItemQuantityUpdater {

    private final OrderItemRepository orderItemRepository;


    public PostgresCartItemQuantityUpdater(OrderItemRepository orderItemRepository) {
        this.orderItemRepository = orderItemRepository;
    }


    @Override
    public void incrementQuantity(Long cartId, Long menuItemId, Double price) {
        orderItemRepository.incrementQuantity(cartId, menuItemId, price);
    }
}
