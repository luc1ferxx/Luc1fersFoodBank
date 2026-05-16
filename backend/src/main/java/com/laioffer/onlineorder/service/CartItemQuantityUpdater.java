package com.laioffer.onlineorder.service;


import java.math.BigDecimal;


public interface CartItemQuantityUpdater {

    void incrementQuantity(Long cartId, Long menuItemId, BigDecimal price);
}
