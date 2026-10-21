package com.laioffer.onlineorder.aftersales;


import java.math.BigDecimal;


/**
 * A line of a case: the order line, the quantity asked for and its amount (unit price x quantity).
 *
 * @param orderItemId the order line ({@code items[].id} of the order)
 * @param currency    the ISO 4217 currency of {@code unitPrice} and {@code amount}
 */
public record AfterSalesLineDto(
        Long orderItemId,
        String menuItemName,
        Integer quantity,
        BigDecimal unitPrice,
        BigDecimal amount,
        String currency
) {

    AfterSalesLineDto(AfterSalesCaseLineEntity line) {
        this(
                line.orderItemId(),
                line.menuItemName(),
                line.quantity(),
                line.unitPrice(),
                line.amount(),
                line.currency()
        );
    }
}
