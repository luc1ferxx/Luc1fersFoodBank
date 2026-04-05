package com.laioffer.onlineorder.service;


import com.laioffer.onlineorder.entity.CartEntity;
import com.laioffer.onlineorder.entity.MenuItemEntity;
import com.laioffer.onlineorder.entity.OrderEntity;
import com.laioffer.onlineorder.entity.OrderHistoryItemEntity;
import com.laioffer.onlineorder.entity.OrderItemEntity;
import com.laioffer.onlineorder.exception.BadRequestException;
import com.laioffer.onlineorder.exception.ResourceNotFoundException;
import com.laioffer.onlineorder.model.CartDto;
import com.laioffer.onlineorder.model.OrderDto;
import com.laioffer.onlineorder.model.OrderHistoryItemDto;
import com.laioffer.onlineorder.model.OrderItemDto;
import com.laioffer.onlineorder.repository.CartRepository;
import com.laioffer.onlineorder.repository.MenuItemRepository;
import com.laioffer.onlineorder.repository.OrderHistoryItemRepository;
import com.laioffer.onlineorder.repository.OrderItemRepository;
import com.laioffer.onlineorder.repository.OrderRepository;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;


@Service
public class CartService {

    private final CartRepository cartRepository;
    private final MenuItemRepository menuItemRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderRepository orderRepository;
    private final OrderHistoryItemRepository orderHistoryItemRepository;


    public CartService(
            CartRepository cartRepository,
            MenuItemRepository menuItemRepository,
            OrderItemRepository orderItemRepository,
            OrderRepository orderRepository,
            OrderHistoryItemRepository orderHistoryItemRepository
    ) {
        this.cartRepository = cartRepository;
        this.menuItemRepository = menuItemRepository;
        this.orderItemRepository = orderItemRepository;
        this.orderRepository = orderRepository;
        this.orderHistoryItemRepository = orderHistoryItemRepository;
    }


    @CacheEvict(cacheNames = "cart", key = "#customerId")
    @Transactional
    public void addMenuItemToCart(long customerId, long menuItemId) {
        CartEntity cart = getRequiredCart(customerId);
        MenuItemEntity menuItem = getRequiredMenuItem(menuItemId);
        OrderItemEntity orderItem = orderItemRepository.findByCartIdAndMenuItemId(cart.id(), menuItem.id());

        Long orderItemId = orderItem == null ? null : orderItem.id();
        int quantity = orderItem == null ? 1 : orderItem.quantity() + 1;

        OrderItemEntity newOrderItem = new OrderItemEntity(orderItemId, menuItem.id(), cart.id(), menuItem.price(), quantity);
        orderItemRepository.save(newOrderItem);
        syncCartTotal(cart.id());
    }


    @Cacheable("cart")
    public CartDto getCart(Long customerId) {
        CartEntity cart = getRequiredCart(customerId);
        return buildCartDto(cart);
    }


    @CacheEvict(cacheNames = "cart", key = "#customerId")
    @Transactional
    public void updateOrderItemQuantity(Long customerId, Long orderItemId, Integer quantity) {
        if (quantity == null || quantity < 0) {
            throw new BadRequestException("Quantity must be zero or greater");
        }

        CartEntity cart = getRequiredCart(customerId);
        OrderItemEntity orderItem = getRequiredOrderItem(cart.id(), orderItemId);

        if (quantity == 0) {
            orderItemRepository.deleteByIdAndCartId(orderItem.id(), cart.id());
        } else {
            OrderItemEntity updatedOrderItem = new OrderItemEntity(
                    orderItem.id(),
                    orderItem.menuItemId(),
                    orderItem.cartId(),
                    orderItem.price(),
                    quantity
            );
            orderItemRepository.save(updatedOrderItem);
        }

        syncCartTotal(cart.id());
    }


    @CacheEvict(cacheNames = "cart", key = "#customerId")
    @Transactional
    public void clearCart(Long customerId) {
        CartEntity cart = getRequiredCart(customerId);
        orderItemRepository.deleteByCartId(cart.id());
        cartRepository.updateTotalPrice(cart.id(), 0.0);
    }


    @CacheEvict(cacheNames = "cart", key = "#customerId")
    @Transactional
    public OrderDto checkout(Long customerId) {
        CartEntity cart = getRequiredCart(customerId);
        List<OrderItemEntity> cartItems = orderItemRepository.getAllByCartId(cart.id());
        if (cartItems.isEmpty()) {
            throw new BadRequestException("Your cart is empty");
        }

        syncCartTotal(cart.id());
        CartEntity refreshedCart = getRequiredCart(customerId);

        OrderEntity savedOrder = orderRepository.save(new OrderEntity(
                null,
                customerId,
                refreshedCart.totalPrice(),
                "PLACED",
                LocalDateTime.now()
        ));

        List<OrderHistoryItemEntity> orderHistoryItems = new ArrayList<>();
        List<OrderHistoryItemDto> orderHistoryItemDtos = new ArrayList<>();
        for (OrderItemEntity cartItem : cartItems) {
            MenuItemEntity menuItem = getRequiredMenuItem(cartItem.menuItemId());
            OrderHistoryItemEntity orderHistoryItem = new OrderHistoryItemEntity(
                    null,
                    savedOrder.id(),
                    cartItem.menuItemId(),
                    menuItem.restaurantId(),
                    cartItem.price(),
                    cartItem.quantity(),
                    menuItem.name(),
                    menuItem.description(),
                    menuItem.imageUrl()
            );
            orderHistoryItems.add(orderHistoryItem);
        }

        for (OrderHistoryItemEntity savedItem : orderHistoryItemRepository.saveAll(orderHistoryItems)) {
            orderHistoryItemDtos.add(new OrderHistoryItemDto(savedItem));
        }

        orderItemRepository.deleteByCartId(cart.id());
        cartRepository.updateTotalPrice(cart.id(), 0.0);

        return new OrderDto(savedOrder, orderHistoryItemDtos);
    }


    private CartEntity getRequiredCart(Long customerId) {
        CartEntity cart = cartRepository.getByCustomerId(customerId);
        if (cart == null) {
            throw new ResourceNotFoundException("Cart not found");
        }
        return cart;
    }


    private MenuItemEntity getRequiredMenuItem(Long menuItemId) {
        return menuItemRepository.findById(menuItemId)
                .orElseThrow(() -> new ResourceNotFoundException("Menu item not found"));
    }


    private OrderItemEntity getRequiredOrderItem(Long cartId, Long orderItemId) {
        OrderItemEntity orderItem = orderItemRepository.findById(orderItemId)
                .orElseThrow(() -> new ResourceNotFoundException("Cart item not found"));
        if (!orderItem.cartId().equals(cartId)) {
            throw new ResourceNotFoundException("Cart item not found");
        }
        return orderItem;
    }


    private CartDto buildCartDto(CartEntity cart) {
        List<OrderItemEntity> orderItems = orderItemRepository.getAllByCartId(cart.id());
        List<OrderItemDto> orderItemDtos = new ArrayList<>();
        for (OrderItemEntity orderItem : orderItems) {
            MenuItemEntity menuItem = getRequiredMenuItem(orderItem.menuItemId());
            orderItemDtos.add(new OrderItemDto(orderItem, menuItem));
        }
        return new CartDto(cart, orderItemDtos);
    }


    private void syncCartTotal(Long cartId) {
        List<OrderItemEntity> orderItems = orderItemRepository.getAllByCartId(cartId);
        double totalPrice = 0.0;
        for (OrderItemEntity orderItem : orderItems) {
            totalPrice += orderItem.price() * orderItem.quantity();
        }
        cartRepository.updateTotalPrice(cartId, totalPrice);
    }
}
