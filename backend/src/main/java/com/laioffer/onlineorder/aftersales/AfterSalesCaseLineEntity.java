package com.laioffer.onlineorder.aftersales;


import com.laioffer.onlineorder.entity.OrderHistoryItemEntity;
import com.laioffer.onlineorder.money.Money;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;


import java.math.BigDecimal;


/**
 * An order line a case asks a refund for.
 *
 * @param orderItemId  the order line (order_history_items.id)
 * @param menuItemName the line's item name, copied from the order line
 * @param unitPrice    the line's unit price, copied from the order line
 * @param quantity     the quantity asked, at most the quantity ordered
 * @param amount       unit price x quantity
 */
@Table("after_sales_case_lines")
public record AfterSalesCaseLineEntity(
        @Id
        @Column("id")
        Long id,
        @Column("case_id")
        Long caseId,
        @Column("order_item_id")
        Long orderItemId,
        @Column("menu_item_name")
        String menuItemName,
        @Column("unit_price")
        BigDecimal unitPrice,
        @Column("quantity")
        Integer quantity,
        @Column("amount")
        BigDecimal amount,
        @Column("currency")
        String currency
) {

    /**
     * A new (unsaved) line asking for {@code quantity} of an order line, at the order line's name and unit price.
     *
     * @param caseId the case it belongs to; null for a line whose case is not saved yet
     */
    public static AfterSalesCaseLineEntity of(Long caseId, OrderHistoryItemEntity item, int quantity) {
        Money amount = item.unitPrice().times(quantity);
        return new AfterSalesCaseLineEntity(
                null,
                caseId,
                item.id(),
                item.menuItemName(),
                item.price(),
                quantity,
                amount.amount(),
                amount.currencyCode()
        );
    }
}
