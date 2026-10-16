package com.laioffer.onlineorder.audit;


import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;


import java.time.LocalDateTime;


/**
 * One audited action (V7_2). {@code details} is a JSON object.
 */
@Table("audit_log")
public record AuditLogEntity(
        @Id
        @Column("id")
        Long id,
        @Column("actor_customer_id")
        Long actorCustomerId,
        @Column("actor_role")
        String actorRole,
        @Column("action")
        String action,
        @Column("target_type")
        String targetType,
        @Column("target_id")
        String targetId,
        @Column("details")
        String details,
        @Column("trace_id")
        String traceId,
        @Column("occurred_at")
        LocalDateTime occurredAt
) {
}
