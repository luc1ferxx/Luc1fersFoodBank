package com.laioffer.onlineorder.fulfilment.merchant;


import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;


import java.math.BigDecimal;
import java.time.LocalDateTime;


/**
 * A row of the merchant's after-sales inbox: the merchant module's projection of a customer's after-sales case, built
 * from after-sales events.
 *
 * @param orderNo     the public order number, which the merchant's after-sales page shows
 * @param lines       JSON array of the case lines, priced in {@code currency}
 * @param caseVersion the case version the row reflects; an event of an older version never overwrites it
 * @param openedAt    when the case was opened; the merchant's deadline counts from it
 */
@Table("merchant_after_sales_cases")
public record MerchantAfterSalesEntity(
        @Id
        @Column("id")
        Long id,
        @Column("case_id")
        Long caseId,
        @Column("order_id")
        Long orderId,
        @Column("order_no")
        Long orderNo,
        @Column("restaurant_id")
        Long restaurantId,
        @Column("customer_id")
        Long customerId,
        @Column("status")
        String status,
        @Column("reason_code")
        String reasonCode,
        @Column("description")
        String description,
        @Column("lines")
        String lines,
        @Column("requested_amount")
        BigDecimal requestedAmount,
        @Column("offered_amount")
        BigDecimal offeredAmount,
        @Column("approved_amount")
        BigDecimal approvedAmount,
        @Column("currency")
        String currency,
        @Column("merchant_decision")
        String merchantDecision,
        @Column("merchant_note")
        String merchantNote,
        @Column("deadline_at")
        LocalDateTime deadlineAt,
        @Column("case_version")
        Long caseVersion,
        @Column("opened_at")
        LocalDateTime openedAt,
        @Column("updated_at")
        LocalDateTime updatedAt
) {
}
