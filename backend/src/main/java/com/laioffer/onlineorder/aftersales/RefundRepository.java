package com.laioffer.onlineorder.aftersales;


import org.springframework.data.jdbc.repository.query.Modifying;
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
     * Claims the next refund that is due, in two claims that each read one index range (ADR 0002 sections 19.11 and
     * 24) instead of one that walks every refund ever made: first a PROCESSING refund whose lease expired (its
     * instance died or hung after the claim), then a PENDING one whose next attempt is due. Expired leases go first:
     * they are few, and a reclaim leases each one again, so they cannot starve the PENDING refunds, while a steady
     * stream of PENDING refunds could starve them. SKIP LOCKED lets pollers of several instances claim different
     * refunds.
     *
     * @return the refund, locked until the caller's transaction ends; null when none is due
     */
    default RefundEntity claimNextDue(LocalDateTime now) {
        RefundEntity expired = claimNextExpiredLease(now);
        return expired != null ? expired : claimNextPending(now);
    }


    /**
     * The PROCESSING refund whose lease expired longest ago (idx_refunds_claim_expiry).
     */
    @Query("""
            SELECT *
            FROM refunds
            WHERE status = 'PROCESSING'
              AND claimed_until <= :now
            ORDER BY claimed_until, id
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """)
    RefundEntity claimNextExpiredLease(LocalDateTime now);


    /**
     * The PENDING refund whose next attempt is the most overdue (idx_refunds_due).
     */
    @Query("""
            SELECT *
            FROM refunds
            WHERE status = 'PENDING'
              AND next_attempt_at <= :now
            ORDER BY next_attempt_at, id
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """)
    RefundEntity claimNextPending(LocalDateTime now);


    /**
     * Backs off a PENDING refund whose claim transaction failed after the claim: its next attempt moves to
     * {@code retryAt} and the failure becomes its last_error, only while the refund is still PENDING at
     * {@code version}. Leaves the version as it is, as {@link AfterSalesCaseRepository#rescheduleDeadline} does.
     * Returns the rows changed, 1 or 0.
     */
    @Modifying
    @Query("""
            UPDATE refunds
            SET next_attempt_at = :retryAt,
                last_error = :lastError,
                updated_at = :now
            WHERE id = :id
              AND version = :version
              AND status = 'PENDING'
            """)
    int backOffPending(Long id, Long version, LocalDateTime retryAt, String lastError, LocalDateTime now);


    /**
     * Backs off a PROCESSING refund whose lease expired and whose reclaim failed after the claim: the end of its lease
     * moves to {@code retryAt}, so that it is not reclaimed before, and the failure becomes its last_error, only while
     * the refund is still PROCESSING at {@code version}. Its attempts stay, since no call was made. Returns the rows
     * changed, 1 or 0.
     */
    @Modifying
    @Query("""
            UPDATE refunds
            SET claimed_until = :retryAt,
                last_error = :lastError,
                updated_at = :now
            WHERE id = :id
              AND version = :version
              AND status = 'PROCESSING'
            """)
    int backOffExpiredLease(Long id, Long version, LocalDateTime retryAt, String lastError, LocalDateTime now);


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
