package com.laioffer.onlineorder.service;


import com.laioffer.onlineorder.entity.OrderItemEntity;
import com.laioffer.onlineorder.repository.OrderItemRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;


@Service
@Profile("h2")
public class H2CartItemQuantityUpdater implements CartItemQuantityUpdater {

    private final OrderItemRepository orderItemRepository;


    public H2CartItemQuantityUpdater(OrderItemRepository orderItemRepository) {
        this.orderItemRepository = orderItemRepository;
    }


    @Override
    public void incrementQuantity(Long cartId, Long menuItemId, Double price) {
        OrderItemEntity existingItem = orderItemRepository.findByCartIdAndMenuItemId(cartId, menuItemId);

        if (existingItem == null) {
            orderItemRepository.save(new OrderItemEntity(null, menuItemId, cartId, price, 1));
            return;
        }

        orderItemRepository.save(new OrderItemEntity(
                existingItem.id(),
                existingItem.menuItemId(),
                existingItem.cartId(),
                price,
                existingItem.quantity() + 1
        ));
    }
}
