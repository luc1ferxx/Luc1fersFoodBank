package com.laioffer.onlineorder.service;


public interface CartItemQuantityUpdater {

    void incrementQuantity(Long cartId, Long menuItemId, Double price);
}
