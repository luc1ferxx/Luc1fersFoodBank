package io.github.luc1ferxx.foodbank.service;


import io.github.luc1ferxx.foodbank.entity.OrderItemEntity;
import io.github.luc1ferxx.foodbank.repository.OrderItemRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;


import io.github.luc1ferxx.foodbank.money.Money;


@Service
@Profile("h2")
public class H2CartItemQuantityUpdater implements CartItemQuantityUpdater {

    private final OrderItemRepository orderItemRepository;


    public H2CartItemQuantityUpdater(OrderItemRepository orderItemRepository) {
        this.orderItemRepository = orderItemRepository;
    }


    @Override
    public void incrementQuantity(Long cartId, Long menuItemId, Money unitPrice) {
        OrderItemEntity existingItem = orderItemRepository.findByCartIdAndMenuItemId(cartId, menuItemId);

        if (existingItem == null) {
            orderItemRepository.save(new OrderItemEntity(
                    null,
                    menuItemId,
                    cartId,
                    unitPrice.amount(),
                    1,
                    unitPrice.currencyCode()
            ));
            return;
        }

        orderItemRepository.save(new OrderItemEntity(
                existingItem.id(),
                existingItem.menuItemId(),
                existingItem.cartId(),
                unitPrice.amount(),
                existingItem.quantity() + 1,
                unitPrice.currencyCode()
        ));
    }
}
