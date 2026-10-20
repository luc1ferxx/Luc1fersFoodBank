package com.laioffer.onlineorder.aftersales;


import java.math.BigDecimal;
import java.time.LocalDateTime;


/**
 * A step of a case, its opening included. The actor is named by type only.
 *
 * @param action     OPEN or ORDER_CANCELLED for the opening, otherwise the after-sales trigger (MERCHANT_REJECT, ...)
 * @param fromStatus absent for the opening
 * @param actorType  CUSTOMER, MERCHANT, ADMIN or SYSTEM
 * @param amount     the amount the step set (requested, offered, approved or refunded); absent for the others
 * @param currency   the ISO 4217 currency of {@code amount}
 * @param note       the merchant's reason, the customer's escalation note or the admin's note
 */
public record AfterSalesHistoryDto(
        String action,
        String fromStatus,
        String toStatus,
        String actorType,
        BigDecimal amount,
        String currency,
        String note,
        LocalDateTime occurredAt
) {

    AfterSalesHistoryDto(AfterSalesCaseHistoryEntity step) {
        this(
                step.action(),
                step.fromStatus(),
                step.toStatus(),
                step.actorType(),
                step.amount(),
                step.currency(),
                step.note(),
                step.occurredAt()
        );
    }
}
