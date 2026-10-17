package com.laioffer.onlineorder.audit;


import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;


import java.time.LocalDateTime;
import java.util.List;


public interface AuditLogRepository extends ListCrudRepository<AuditLogEntity, Long> {

    @Query("""
            SELECT *
            FROM audit_log
            ORDER BY id DESC
            LIMIT :limit
            """)
    List<AuditLogEntity> findNewest(int limit);


    @Query("""
            SELECT *
            FROM audit_log
            WHERE id < :beforeId
            ORDER BY id DESC
            LIMIT :limit
            """)
    List<AuditLogEntity> findNewestBefore(long beforeId, int limit);


    /**
     * Retention by rows where audit_log is not partitioned (H2) and app.cleanup.audit-log-retention is set; on
     * PostgreSQL whole months are dropped instead.
     */
    @Modifying
    @Query("""
            DELETE FROM audit_log
            WHERE id IN (
                SELECT id
                FROM audit_log
                WHERE occurred_at < :occurredBefore
                ORDER BY id
                LIMIT :batchSize
            )
            """)
    int deleteByOccurredAtBefore(LocalDateTime occurredBefore, int batchSize);
}
