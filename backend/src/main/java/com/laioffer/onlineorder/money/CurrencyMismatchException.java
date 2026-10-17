package com.laioffer.onlineorder.money;


/**
 * Two amounts of different currencies met in one calculation. Carts and orders are single-currency by construction
 * (single-restaurant, and the database ties every cart line to its menu item's and its cart's currency), so this
 * signals a bug, not a user error.
 */
public class CurrencyMismatchException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;


    public CurrencyMismatchException(Money left, Money right) {
        super("Cannot combine " + left + " and " + right + ": the amounts are in different currencies");
    }
}
