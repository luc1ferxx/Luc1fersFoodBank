package com.laioffer.onlineorder.aftersales;


import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;


import java.math.BigDecimal;
import java.time.LocalDateTime;


/**
 * One step of a case, its opening included: who took it (an {@code OrderActor} type and id) and the amount it set, if
 * any.
 *
 * @param fromStatus null for the opening
 * @param action     the trigger name, or OPEN / ORDER_CANCELLED for the opening
 * @param traceId    the OpenTelemetry trace id of the request or job that took the step
 */
@Table("after_sales_case_history")
public record AfterSalesCaseHistoryEntity(
        @Id
        @Column("id")
        Long id,
        @Column("case_id")
        Long caseId,
        @Column("from_status")
        String fromStatus,
        @Column("to_status")
        String toStatus,
        @Column("action")
        String action,
        @Column("actor_type")
        String actorType,
        @Column("actor_id")
        Long actorId,
        @Column("amount")
        BigDecimal amount,
        @Column("currency")
        String currency,
        @Column("note")
        String note,
        @Column("trace_id")
        String traceId,
        @Column("occurred_at")
        LocalDateTime occurredAt
) {
}
