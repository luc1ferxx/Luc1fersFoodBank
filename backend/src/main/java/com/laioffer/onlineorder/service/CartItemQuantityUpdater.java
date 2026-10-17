package com.laioffer.onlineorder.service;


import com.laioffer.onlineorder.money.Money;


public interface CartItemQuantityUpdater {

    /**
     * Adds one unit of a menu item to a cart, or a first line priced at {@code unitPrice}. The cart must already be in
     * {@code unitPrice}'s currency (the database rejects a line in another currency than its cart's).
     */
    void incrementQuantity(Long cartId, Long menuItemId, Money unitPrice);
}
