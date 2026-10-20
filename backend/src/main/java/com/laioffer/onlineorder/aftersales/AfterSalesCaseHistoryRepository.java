package com.laioffer.onlineorder.aftersales;


import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;


import java.util.List;


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
}
