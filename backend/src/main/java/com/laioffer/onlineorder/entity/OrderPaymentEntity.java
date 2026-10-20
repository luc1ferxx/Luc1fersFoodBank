package com.laioffer.onlineorder.entity;


import com.laioffer.onlineorder.money.Money;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;


import java.math.BigDecimal;
import java.time.LocalDateTime;


/**
 * The capture of a paid checkout (ADR 0002 D16), one per order at most. Refunds are only made against a capture and
 * never exceed its amount.
 *
 * @param status            {@link #CAPTURED}, the only status a payment has
 * @param providerReference the payment provider's id of the capture ({@code SIMPAY-...} from the simulated provider),
 *                          which a refund names
 */
@Table("order_payments")
public record OrderPaymentEntity(
        @Id
        @Column("id")
        Long id,
        @Column("order_id")
        Long orderId,
        @Column("customer_id")
        Long customerId,
        @Column("amount")
        BigDecimal amount,
        @Column("currency")
        String currency,
        @Column("status")
        String status,
        @Column("provider_reference")
        String providerReference,
        @Column("captured_at")
        LocalDateTime capturedAt
) {

    public static final String CAPTURED = "CAPTURED";


    public Money captured() {
        return Money.of(amount, currency);
    }
}
