package com.laioffer.onlineorder.audit;


import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;


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
}
