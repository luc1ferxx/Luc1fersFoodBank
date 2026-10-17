package com.laioffer.onlineorder.money;


/**
 * An amount larger than a NUMERIC(12,2) column stores ({@link Money#MAX_AMOUNT}).
 */
public class MoneyOverflowException extends ArithmeticException {

    private static final long serialVersionUID = 1L;


    public MoneyOverflowException(String message) {
        super(message);
    }
}
