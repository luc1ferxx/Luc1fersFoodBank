package com.laioffer.onlineorder.fulfilment.timeout;


import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;


import java.time.LocalDateTime;


@Table("order_deadlines")
public record OrderDeadlineEntity(
        @Id
        @Column("id")
        Long id,
        @Column("order_id")
        Long orderId,
        @Column("deadline_type")
        String deadlineType,
        @Column("due_at")
        LocalDateTime dueAt,
        @Column("attempts")
        Integer attempts,
        @Column("last_error")
        String lastError,
        @Column("created_at")
        LocalDateTime createdAt,
        @Column("updated_at")
        LocalDateTime updatedAt
) {
}
