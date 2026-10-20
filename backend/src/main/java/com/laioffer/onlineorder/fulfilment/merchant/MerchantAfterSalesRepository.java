package com.laioffer.onlineorder.fulfilment.merchant;


import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;


import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;


public interface MerchantAfterSalesRepository extends ListCrudRepository<MerchantAfterSalesEntity, Long> {

    /**
     * Adds the inbox row of a case unless the case already has one, and returns the rows inserted: 1 or 0.
     * Target-less ON CONFLICT so that H2 (PostgreSQL mode) parses it; the only constraint an insert can violate is
     * uq_merchant_after_sales_case.
     */
    @Modifying
    @Query("""
            INSERT INTO merchant_after_sales_cases (
                case_id,
                order_id,
                order_no,
                restaurant_id,
                customer_id,
                status,
                reason_code,
                description,
                lines,
                requested_amount,
                offered_amount,
                approved_amount,
                currency,
                merchant_decision,
                merchant_note,
                deadline_at,
                case_version,
                opened_at,
                updated_at
            ) VALUES (
                :caseId,
                :orderId,
                :orderNo,
                :restaurantId,
                :customerId,
                :status,
                :reasonCode,
                :description,
                :lines,
                :requestedAmount,
                :offeredAmount,
                :approvedAmount,
                :currency,
                :merchantDecision,
                :merchantNote,
                :deadlineAt,
                :caseVersion,
                :openedAt,
                :updatedAt
            )
            ON CONFLICT DO NOTHING
            """)
    int insertIfAbsent(
            Long caseId,
            Long orderId,
            Long orderNo,
            Long restaurantId,
            Long customerId,
            String status,
            String reasonCode,
            String description,
            String lines,
            BigDecimal requestedAmount,
            BigDecimal offeredAmount,
            BigDecimal approvedAmount,
            String currency,
            String merchantDecision,
            String merchantNote,
            LocalDateTime deadlineAt,
            Long caseVersion,
            LocalDateTime openedAt,
            LocalDateTime updatedAt
    );


    /**
     * Brings the row of a case to {@code caseVersion}, only if it reflects an older version, so that a late or
     * replayed event never moves a row back. Returns the rows changed, 1 or 0.
     */
    @Modifying
    @Query("""
            UPDATE merchant_after_sales_cases
            SET status = :status,
                offered_amount = :offeredAmount,
                approved_amount = :approvedAmount,
                merchant_decision = :merchantDecision,
                merchant_note = :merchantNote,
                deadline_at = :deadlineAt,
                case_version = :caseVersion,
                updated_at = :updatedAt
            WHERE case_id = :caseId
              AND case_version < :caseVersion
            """)
    int updateIfNewer(
            Long caseId,
            String status,
            BigDecimal offeredAmount,
            BigDecimal approvedAmount,
            String merchantDecision,
            String merchantNote,
            LocalDateTime deadlineAt,
            Long caseVersion,
            LocalDateTime updatedAt
    );


    @Query("""
            SELECT *
            FROM merchant_after_sales_cases
            WHERE case_id = :caseId
              AND restaurant_id = :restaurantId
            FOR UPDATE
            """)
    MerchantAfterSalesEntity lockByCaseIdAndRestaurantId(Long caseId, Long restaurantId);


    @Query("""
            SELECT *
            FROM merchant_after_sales_cases
            WHERE restaurant_id = :restaurantId
            ORDER BY opened_at DESC, id DESC
            LIMIT :limit
            """)
    List<MerchantAfterSalesEntity> findPageByRestaurantId(Long restaurantId, int limit);


    @Query("""
            SELECT *
            FROM merchant_after_sales_cases
            WHERE restaurant_id = :restaurantId
              AND status = :status
            ORDER BY opened_at DESC, id DESC
            LIMIT :limit
            """)
    List<MerchantAfterSalesEntity> findPageByRestaurantIdAndStatus(Long restaurantId, String status, int limit);
}
