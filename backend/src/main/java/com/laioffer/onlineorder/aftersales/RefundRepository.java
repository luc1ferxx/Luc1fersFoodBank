package com.laioffer.onlineorder.aftersales;


import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;


import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;


/**
 * Refunds are inserted and changed with {@code save}; its version check makes a result recorded after the lease
 * expired and another instance reclaimed the refund fail instead of overwriting the newer attempt.
 */
public interface RefundRepository extends ListCrudRepository<RefundEntity, Long> {

    @Query("""
            SELECT *
            FROM refunds
            WHERE id = :id
            FOR UPDATE
            """)
    RefundEntity lockById(Long id);


    /**
     * Claims the oldest refund that is due: a PENDING one whose next attempt is due, or a PROCESSING one whose lease
     * expired (its instance died or hung after the claim). SKIP LOCKED lets pollers of several instances claim
     * different refunds.
     */
    @Query("""
            SELECT *
            FROM refunds
            WHERE (status = 'PENDING' AND next_attempt_at <= :now)
               OR (status = 'PROCESSING' AND claimed_until <= :now)
            ORDER BY id
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """)
    RefundEntity claimNextDue(LocalDateTime now);


    /**
     * @param caseIds not empty
     */
    @Query("""
            SELECT *
            FROM refunds
            WHERE case_id IN (:caseIds)
            ORDER BY id
            """)
    List<RefundEntity> findAllByCaseIdIn(Collection<Long> caseIds);


    @Query("""
            SELECT *
            FROM refunds
            WHERE status = :status
            ORDER BY id DESC
            LIMIT :limit
            """)
    List<RefundEntity> findPageByStatus(String status, int limit);
}
