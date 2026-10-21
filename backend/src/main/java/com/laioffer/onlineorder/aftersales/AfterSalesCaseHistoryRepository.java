package com.laioffer.onlineorder.aftersales;


import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;


import java.util.List;
import java.util.Optional;


public interface AfterSalesCaseHistoryRepository extends ListCrudRepository<AfterSalesCaseHistoryEntity, Long> {

    /**
     * The steps of a case, oldest first.
     */
    @Query("""
            SELECT *
            FROM after_sales_case_history
            WHERE case_id = :caseId
            ORDER BY occurred_at, id
            """)
    List<AfterSalesCaseHistoryEntity> findAllByCaseId(Long caseId);

    /**
     * The status the customer withdrew the case from: the from_status of its CUSTOMER_WITHDRAW step, which a case has
     * at most once (CLOSED is final). Empty when the case was not withdrawn.
     */
    @Query("""
            SELECT from_status
            FROM after_sales_case_history
            WHERE case_id = :caseId
              AND action = 'CUSTOMER_WITHDRAW'
            ORDER BY occurred_at, id
            LIMIT 1
            """)
    Optional<String> findWithdrawnFromStatus(Long caseId);
}
